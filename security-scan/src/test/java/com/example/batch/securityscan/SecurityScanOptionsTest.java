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
}
