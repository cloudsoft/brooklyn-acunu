package io.cloudsoft.acunu.analyticstier;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.nosql.cassandra.CassandraCluster;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.entity.trait.Startable;
import brooklyn.entity.webapp.WebAppService;

@ImplementedBy(AcunuAnalyticsServerImpl.class)
public interface AcunuAnalyticsServer extends Entity, Startable, WebAppService {

    ConfigKey<String> DOWNLOAD_URL = ConfigKeys.newConfigKeyWithDefault(
            Attributes.DOWNLOAD_URL.getConfigKey(), "file:///tmp/");

    ConfigKey<CassandraCluster> CASSANDRA_CLUSTER = ConfigKeys.newConfigKey(CassandraCluster.class, "acunu.target.cassandra.cluster.entity");

}
