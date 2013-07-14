package io.cloudsoft.acunu.analyticstier;

import java.util.Collection;

import brooklyn.enricher.basic.SensorPropagatingEnricher;
import brooklyn.entity.annotation.EffectorParam;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.BasicEntityImpl;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.java.UsesJavaMXBeans;
import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.entity.trait.StartableMethods;
import brooklyn.entity.webapp.JavaWebAppService;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.entity.webapp.jetty.Jetty6Server;
import brooklyn.location.Location;
import brooklyn.util.net.Urls;
import brooklyn.util.text.WildcardGlobs;


public class AcunuAnalyticsServerImpl extends BasicEntityImpl implements AcunuAnalyticsServer {

    @Override
    public void init() {
        super.init();
        AcunuAnalyticsJetty jetty = addChild(EntitySpecs.spec(AcunuAnalyticsJetty.class).
                configure(AcunuAnalyticsJetty.ACUNU_CONF_TGZ_DOWNLOAD_URL,
                        Urls.mergePaths(getConfig(DOWNLOAD_URL),"acunu-conf.tgz")).
                configure(AcunuAnalyticsJetty.ACUNU_KEYSPACE_INIT_WAR_URL,
                        Urls.mergePaths(getConfig(DOWNLOAD_URL),"analytics.war")).
                configure(JavaWebAppService.NAMED_WARS,
                    WildcardGlobs.getGlobsAfterBraceExpansion(
                        Urls.mergePaths(getConfig(DOWNLOAD_URL),"{analytics,aql,dashboards}.war") )));
        
        addEnricher(SensorPropagatingEnricher.newInstanceListeningTo(jetty,
                ROOT_URL, Attributes.HOSTNAME, HTTP_PORT, 
                SoftwareProcess.SERVICE_STATE, SERVICE_UP,  
                REQUEST_COUNT, ERROR_COUNT,
                REQUESTS_PER_SECOND_LAST, TOTAL_PROCESSING_TIME,
                REQUESTS_PER_SECOND_IN_WINDOW, WebAppService.PROCESSING_TIME_FRACTION_IN_WINDOW,
                UsesJavaMXBeans.PROCESS_CPU_TIME_FRACTION_IN_WINDOW
            ));
    }
    
    public void start(@EffectorParam(name = "locations") Collection<? extends Location> locations) {
        StartableMethods.start(this, locations);
    }

    public void stop() {
        StartableMethods.stop(this);
    }

    public void restart() {
        StartableMethods.restart(this);
    }
    
}
