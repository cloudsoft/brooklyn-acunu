package io.cloudsoft.acunu.analyticstier;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.nosql.cassandra.CassandraCluster;
import brooklyn.entity.nosql.cassandra.CassandraNode;
import brooklyn.entity.webapp.jetty.Jetty6ServerImpl;
import brooklyn.entity.webapp.jetty.Jetty6SshDriver;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.Sensors;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.internal.ssh.SshTool;
import brooklyn.util.ssh.CommonCommands;
import brooklyn.util.task.Tasks;
import brooklyn.util.text.Strings;
import brooklyn.util.time.Duration;
import brooklyn.util.time.Time;

import com.google.common.base.Preconditions;

public class AcunuAnalyticsJettySshDriver extends Jetty6SshDriver implements AcunuAnalyticsJettyDriver {

    public AcunuAnalyticsJettySshDriver(Jetty6ServerImpl entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    private final static String ANALYTICS_YAML_PATH = "/etc/acunu/cassandra.conf/analytics.yaml";
    
    @Override
    public void customize() {
        super.customize();
        
        // install acunu files
        try {
            getMachine().acquireMutex("acunu-conf", "configuring acunu (initial customize of "+getEntity()+")");
            boolean acunuInstalled = newScript("CUSTOMIZING-check-acunu").body.append("ls /etc/acunu").execute() == 0;
            if (acunuInstalled) {
                log.debug("acunu configuration already detected in /etc/acunu on "+getMachine()+" when configuring "+getEntity()+"; skipping");
            } else {
                checkRoot();
                String confTgzUrl = Preconditions.checkNotNull(entity.getConfig(AcunuAnalyticsJetty.ACUNU_CONF_TGZ_DOWNLOAD_URL), AcunuAnalyticsJetty.ACUNU_CONF_TGZ_DOWNLOAD_URL);
                String confTgzFile = getInstallDir()+"/acunu-conf.tgz";
                copyResource(confTgzUrl, confTgzFile);
                newScript("CUSTOMIZING-install-acunu-conf").
                    failOnNonZeroResultCode().
                    setFlag(SshTool.PROP_RUN_AS_ROOT, true).
                    body.append(
                            "mkdir /etc/acunu",
                            "cd /etc/acunu",
                            CommonCommands.INSTALL_TAR,
                            "tar xvfz "+confTgzFile,
                            
                            // TODO some things want conf/ whereas others want cassandra.conf/
                            // would be better to set a location for everything that needs it
                            "rm -rf conf",
                            "mkdir conf",
                            "ln -s `pwd`/cassandra.conf/analytics.yaml conf/analytics.yaml ",
                            
                            // finally use a marker so we know what we have updated
                            // (could just rely on hosts: prefix but that seems more brittle
                            "sed -i.bk 's/hosts: \\\"localhost:9160\\\"/hosts: \\\"localhost:9160\\\"  # cassandra hosts, auto-generated by brooklyn/' "+ANALYTICS_YAML_PATH
                        ).execute();
            }
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        } finally {
            getMachine().releaseMutex("acunu-conf");
        }
        
        // TODO setup cassandra schema -- if not already done
    }
    
    @Override
    public void launch() {
        updateAnalyticsYaml();
        
//        ensureKeyspaceInitialized();
//        super.launch();
        
        // normally the above two lines should work
        // but we are hitting an error where the second node fails trying to create HLock;
        // so checking that ensuring the first one runs before anything else
        // and using horrible global synch block to ensure the first node starts first
        
        boolean needToRunInitialize = !isKeyspaceInitialized();
        if (needToRunInitialize) {
            synchronized (AcunuAnalyticsJettySshDriver.class) {
                needToRunInitialize = !isKeyspaceInitialized();
                if (needToRunInitialize) {
                    runKeyspaceInitialization();
                    super.launch();
                    // give the server time to start
                    Time.sleep(Duration.TEN_SECONDS);
                    // mark it as initialized, but *after* we have launched, and with a delay
                    CassandraCluster cassandra = Preconditions.checkNotNull(entity.getConfig(AcunuAnalyticsServer.CASSANDRA_CLUSTER), AcunuAnalyticsServer.CASSANDRA_CLUSTER);
                    ((EntityLocal)cassandra).setAttribute(CASSANDRA_KEYSPACE_FOR_ACUNU_SETUP_TIMESTAMP, Calendar.getInstance());
                }
            }
        }
        if (!needToRunInitialize) {
            super.launch();
        }
    }

    protected void checkRoot() {
        if (newScript("CUSTOMIZING-install-acunu-conf").
                body.append(CommonCommands.sudo("date")).execute() != 0)
            throw new IllegalStateException("sudo failed at "+getMachine()+" fot "+getEntity());
    }
    
    public int getQuorumSize() {
        return 1;
        // more than 1 breaks KeyspaceInitializer
//        // TODO could be configurable
//        CassandraCluster cassandra = Preconditions.checkNotNull(entity.getConfig(AcunuAnalyticsServer.CASSANDRA_CLUSTER), AcunuAnalyticsServer.CASSANDRA_CLUSTER);
//        int size = cassandra.getConfig(CassandraCluster.INITIAL_SIZE);
//        if (size>3 || size<=0) return 3;
//        return size;
    }
    
    protected void updateAnalyticsYaml() {
        String currentQuorum = Strings.join(waitOnCassandraQuorum(getQuorumSize()), ",");
        
        try {
            getMachine().acquireMutex("acunu-conf", "configuring acunu (hosts update of "+getEntity()+")");
            // TODO could check hosts again
            checkRoot();
            newScript("UPDATING-install-acunu-conf").
                failOnNonZeroResultCode().
                setFlag(SshTool.PROP_RUN_AS_ROOT, true).
                body.append(
                        "cd /etc/acunu",
                        "sed -i.bk 's/hosts: .*  # cassandra hosts/hosts: \\\""+currentQuorum+"\\\"  # cassandra hosts/' "+ANALYTICS_YAML_PATH
                    ).execute();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        } finally {
            getMachine().releaseMutex("acunu-conf");
        }
    }

    protected List<String> waitOnCassandraQuorum(int minSize) {
        Tasks.setBlockingDetails("waiting on Cassandra quorum (size "+minSize+")");
        int count = 0;
        Duration toWait = Duration.millis(100);
        List<String> result;
        while (true) {
            result = getCassandraQuorum(minSize);
            if (result.size() >= minSize)
                break;
            if (count==0) {
                log.info("waiting in "+entity+" setup for cassandra quorum (size "+minSize+")");
            } else {
                log.debug("waiting ("+toWait+") in "+entity+" config for cassandra quorum - "+result);
            }
            Time.sleep(toWait);
            toWait = toWait.times(2);
            if (toWait.compareTo(Duration.FIVE_SECONDS)>0) toWait = Duration.FIVE_SECONDS;
        }
        log.debug("got cassandra quorum at "+this+" - "+result);
        Tasks.setBlockingDetails(null);
        return result;
    }

    protected List<String> getCassandraQuorum(int minSize) {
        CassandraCluster cassandra = Preconditions.checkNotNull(entity.getConfig(AcunuAnalyticsServer.CASSANDRA_CLUSTER), AcunuAnalyticsServer.CASSANDRA_CLUSTER);
        List<String> result = new ArrayList<String>();
        for (Entity child: cassandra.getChildren()) {
            if (child instanceof CassandraNode) {
                Boolean up = ((CassandraNode)child).getAttribute(CassandraNode.SERVICE_UP);
                if (Boolean.TRUE == up) {
                    String host = ((CassandraNode)child).getAttribute(CassandraNode.ADDRESS);
                    Integer port = ((CassandraNode)child).getAttribute(CassandraNode.THRIFT_PORT);
                    if (host!=null && port!=null) {
                        result.add(host+":"+port);
                        if (minSize>0 && result.size() >= minSize)
                            return result;
                    }
                }
            }
        }
        return result;
    }


    private static final AttributeSensor<Calendar> CASSANDRA_KEYSPACE_FOR_ACUNU_SETUP_TIMESTAMP =
            Sensors.newSensor(Calendar.class, "acunu.cassandra.keyspace.setup.timestamp");

    private boolean isKeyspaceInitialized() {
        CassandraCluster cassandra = Preconditions.checkNotNull(entity.getConfig(AcunuAnalyticsServer.CASSANDRA_CLUSTER), AcunuAnalyticsServer.CASSANDRA_CLUSTER);
        return (cassandra.getAttribute(CASSANDRA_KEYSPACE_FOR_ACUNU_SETUP_TIMESTAMP)!=null);
    }

    protected void ensureKeyspaceInitialized() {
        CassandraCluster cassandra = Preconditions.checkNotNull(entity.getConfig(AcunuAnalyticsServer.CASSANDRA_CLUSTER), AcunuAnalyticsServer.CASSANDRA_CLUSTER);
        if (isKeyspaceInitialized()) 
            // cassandra setup for acunu done 
            return ;
        
        // need to make sure this command only runs once - this is a messy way, will not distribute
        synchronized (AcunuAnalyticsJettySshDriver.class) {
            // TODO use MutexSupport on some shared object ... maybe Iterables.getOnlyElement(cassandra.getLocations()) ?
            // (instead of synching on Object)
            
            if (isKeyspaceInitialized())
                // cassandra setup for acunu done 
                return;

            runKeyspaceInitialization();
            
            ((EntityLocal)cassandra).setAttribute(CASSANDRA_KEYSPACE_FOR_ACUNU_SETUP_TIMESTAMP, Calendar.getInstance());
        }
    }
    
    protected void runKeyspaceInitialization() {
        log.info("Running cassandra-acunu keyspace initialization from "+getEntity()+" @ "+getMachine());
        newScript("launch-initializing-1-cassandra-acunu").
            failOnNonZeroResultCode().
            body.append(
                "cd "+getRunDir(),
                "mkdir tmp-for-keyspace-initialization",
                "cd tmp-for-keyspace-initialization"
        ).execute();
        copyResource(Preconditions.checkNotNull(getEntity().getConfig(AcunuAnalyticsJetty.ACUNU_KEYSPACE_INIT_WAR_URL)), 
                getRunDir()+"/"+"tmp-for-keyspace-initialization/keyspace-init.war");
        newScript("launch-initializing-2-cassandra-acunu").
            failOnNonZeroResultCode().
            body.append(
                    "cd "+getRunDir(),
                    "cd tmp-for-keyspace-initialization",
                    "unzip keyspace-init.war",
                    // really simple way to make the calsspath and run the command
                    "cd WEB-INF/lib/",
                    "for x in *.jar ; do echo -n $x\":\" >> CLASSPATH_ACUNU ; done",
                    "java -cp `cat CLASSPATH_ACUNU`: -Danalytics.config.dir=/etc/acunu/cassandra.conf/ com.acunu.analytics.config.KeyspaceInitializer"
            ).execute();
    }

}
