package com.example.batch.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.security")
public class BatchSecurityProperties {

    /**
     * Early-testing mode: relax authentication, masking, and decryption gates.
     */
    private boolean testingOpen = false;

    public boolean isTestingOpen() {
        return testingOpen;
    }

    public void setTestingOpen(boolean testingOpen) {
        this.testingOpen = testingOpen;
    }

    /**
     * Demo mode: allow console admin access and return exception stack traces
     * (for front-end integration debugging).
     */
    private boolean demoOpen = false;

    public boolean isDemoOpen() {
        return demoOpen;
    }

    public void setDemoOpen(boolean demoOpen) {
        this.demoOpen = demoOpen;
    }
}
