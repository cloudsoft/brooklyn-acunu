package io.cloudsoft.acunu.analyticstier;

import brooklyn.entity.webapp.jetty.Jetty6ServerImpl;

public class AcunuAnalyticsJettyImpl extends Jetty6ServerImpl implements AcunuAnalyticsJetty {

    @Override
    public Class getDriverInterface() {
        return AcunuAnalyticsJettyDriver.class;
    }
    
}
