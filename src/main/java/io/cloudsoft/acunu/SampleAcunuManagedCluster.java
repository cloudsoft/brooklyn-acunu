package io.cloudsoft.acunu;

import io.cloudsoft.acunu.analyticstier.AcunuAnalyticsServer;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.catalog.CatalogConfig;
import brooklyn.config.ConfigKey;
import brooklyn.enricher.CustomAggregatingEnricher;
import brooklyn.enricher.HttpLatencyDetector;
import brooklyn.enricher.basic.SensorPropagatingEnricher;
import brooklyn.enricher.basic.SensorTransformingEnricher;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.java.UsesJavaMXBeans;
import brooklyn.entity.nosql.cassandra.CassandraCluster;
import brooklyn.entity.proxy.AbstractController;
import brooklyn.entity.proxying.BasicEntitySpec;
import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;
import brooklyn.entity.webapp.DynamicWebAppCluster;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.Sensors;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.location.basic.PortRanges;
import brooklyn.policy.autoscaling.AutoScalerPolicy;
import brooklyn.util.CommandLineUtil;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.text.StringFunctions;
import brooklyn.util.time.Duration;

import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

public class SampleAcunuManagedCluster extends AbstractApplication {
    
    public static final Logger LOG = LoggerFactory.getLogger(SampleAcunuManagedCluster.class);

    @CatalogConfig(label = "Analytics tier size")
    public final static ConfigKey<Integer> INITIAL_SIZE_ANALYTICS = 
            ConfigKeys.newIntegerConfigKey("acunu.analyticsTier.size.initial", "Initial size of the Acunu Analytics tier", 2);

    @CatalogConfig(label = "Cassandra tier size")
    public final static ConfigKey<Integer> INITIAL_SIZE_CASSANDRA = 
            ConfigKeys.newIntegerConfigKey("acunu.cassandraTier.size.initial", "Initial size of the Cassandra tier", 4);

    @CatalogConfig(label = "Load-Balanced")
    public final static ConfigKey<Boolean> LOAD_BALANCED_ANALYTICS_ON = 
            ConfigKeys.newBooleanConfigKey("acunu.analyticsTier.loadBalanced", "Whether the Acunu Analytics tier includes a load-balancer (if false, there is only one analytics node)", true);

    @CatalogConfig(label = "Auto-Scaling")
    public final static ConfigKey<Boolean> AUTOSCALING_ON = 
            ConfigKeys.newBooleanConfigKey("acunu.autoscalingOn", "Whether the deployment should autoscale", true);

    public static final AttributeSensor<Double> MEAN_ANALYTICS_NODE_CPU_TIME_FRACTION = Sensors.newDoubleSensor( 
            "acunu.analyticsTier.cpuTime.fraction.windowed.perNode", "Fraction of CPU time used, reported by JVM (percentage, over time window) averaged over all analytics node");

    private CassandraCluster cassandra;
    private Entity analytics;

    @Override
    public void init() {
        cassandra = addChild(EntitySpecs.spec(CassandraCluster.class).configure(
                CassandraCluster.INITIAL_SIZE, getConfig(INITIAL_SIZE_CASSANDRA)) );
        
        if (!getConfig(LOAD_BALANCED_ANALYTICS_ON)) {
            // single instance
            Object explicitAnalyticsSize = getConfigMap().getRawConfig(INITIAL_SIZE_ANALYTICS);
            if (explicitAnalyticsSize!=null && !explicitAnalyticsSize.equals(1))
                log.warn("Analytics size "+explicitAnalyticsSize+" is being ignored because analytics tier is not load-balanced.");
            analytics = addChild(configureForCassandra(EntitySpecs.spec(AcunuAnalyticsServer.class)));
            analytics.addEnricher(SensorTransformingEnricher.newInstanceTransforming(
                    analytics, UsesJavaMXBeans.PROCESS_CPU_TIME_FRACTION_IN_WINDOW, Functions.<Double>identity(), MEAN_ANALYTICS_NODE_CPU_TIME_FRACTION));
        } else {
            analytics = addChild(configureForCassandra(createAcunuClusterSpec()));
            DynamicWebAppCluster analyticsCluster = ((ControlledDynamicWebAppCluster)analytics).getCluster();
            analyticsCluster.addEnricher(
                    CustomAggregatingEnricher.newAveragingEnricher(MutableMap.of("allMembers", true), 
                        UsesJavaMXBeans.PROCESS_CPU_TIME_FRACTION_IN_WINDOW, MEAN_ANALYTICS_NODE_CPU_TIME_FRACTION));
            analytics.addEnricher(SensorPropagatingEnricher.newInstanceListeningTo(analyticsCluster, MEAN_ANALYTICS_NODE_CPU_TIME_FRACTION));
        }
        
        // expose some KPI's
        addEnricher(HttpLatencyDetector.builder().
                url(WebAppService.ROOT_URL).
                rollup(Duration.TEN_SECONDS).
                build());
        addEnricher(new SensorTransformingEnricher<String,String>(analytics, WebAppService.ROOT_URL,
                WebAppService.ROOT_URL, StringFunctions.append("dashboards/")));
        addEnricher(SensorPropagatingEnricher.newInstanceListeningTo(analytics,
                DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW_PER_NODE,
                MEAN_ANALYTICS_NODE_CPU_TIME_FRACTION));
        
        // simple scaling policy, if appropriate
        if (analytics instanceof ControlledDynamicWebAppCluster) {
            if (getConfig(AUTOSCALING_ON)) {
                ((ControlledDynamicWebAppCluster)analytics).getCluster().addPolicy(AutoScalerPolicy.builder().
                    metric(DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW_PER_NODE).
                    metricRange(10, 100).
                    sizeRange(2, 10).
                    build());
            }
        }
    }

    protected <T extends Entity> BasicEntitySpec<T,?> configureForCassandra(BasicEntitySpec<T,?> acunuSpec) {
        Preconditions.checkNotNull(cassandra, "cassandra");
        acunuSpec.configure(AcunuAnalyticsServer.CASSANDRA_CLUSTER, cassandra);
        return acunuSpec;
    }

    public BasicEntitySpec<ControlledDynamicWebAppCluster,?> createAcunuClusterSpec() {
        return EntitySpecs.spec(ControlledDynamicWebAppCluster.class)
                .configure(ControlledDynamicWebAppCluster.MEMBER_SPEC, EntitySpecs.spec(AcunuAnalyticsServer.class))
                .configure(ControlledDynamicWebAppCluster.INITIAL_SIZE, getConfig(INITIAL_SIZE_ANALYTICS))
                .configure(WebAppService.HTTP_PORT, PortRanges.fromString("8080+"))
                .configure(AbstractController.PROXY_HTTP_PORT, PortRanges.fromString("80,8000+"))
            ;
    }

    public static void main(String[] argv) {
        List<String> args = Lists.newArrayList(argv);
        String port =  CommandLineUtil.getCommandLineOption(args, "--port", "8081+");
        String location = CommandLineUtil.getCommandLineOption(args, "--location", "localhost");

        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                .application(EntitySpecs.appSpec(SampleAcunuManagedCluster.class).displayName("Acunu Managed Cluster"))
                .webconsolePort(port)
                .location(location)
                .start();
         
        Entities.dumpInfo(launcher.getApplications());
    }

}
