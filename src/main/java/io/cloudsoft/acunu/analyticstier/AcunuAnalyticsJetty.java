package io.cloudsoft.acunu.analyticstier;

import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.entity.webapp.jetty.Jetty6Server;

@ImplementedBy(AcunuAnalyticsJettyImpl.class)
public interface AcunuAnalyticsJetty extends Jetty6Server {

    ConfigKey<String> ACUNU_CONF_TGZ_DOWNLOAD_URL = ConfigKeys.newStringConfigKey("acunu.conf.tgz.download.url");
    ConfigKey<String> ACUNU_KEYSPACE_INIT_WAR_URL = ConfigKeys.newStringConfigKey("acunu.keyspaceInit.war.url");

}
