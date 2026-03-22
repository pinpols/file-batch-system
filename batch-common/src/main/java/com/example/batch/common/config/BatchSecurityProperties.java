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
}
