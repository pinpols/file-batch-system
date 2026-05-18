package com.example.batch.securityscan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityScanOptionsTest {

    @Test
    void parseDefaultsAndFlags() {
        SecurityScanOptions options = SecurityScanOptions.parse(new String[]{"--mode=secret", "--continue-on-error", "--dry-run"});

        assertFalse(options.help());
        assertEquals(ScanMode.SECRET, options.mode());
        assertTrue(options.continueOnError());
        assertTrue(options.dryRun());
    }

    @Test
    void parseDastAuthOptions() {
        SecurityScanOptions options = SecurityScanOptions.parse(new String[]{
                "--mode=dast",
                "--zap-auth-header-name=Cookie",
                "--zap-auth-header-value=batch_console_token=test"
        });

        assertEquals(ScanMode.DAST, options.mode());
        assertEquals("Cookie", options.zapAuthHeaderName());
        assertEquals("batch_console_token=test", options.zapAuthHeaderValue());
    }
}
