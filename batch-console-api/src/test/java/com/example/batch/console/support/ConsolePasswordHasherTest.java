package com.example.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConsolePasswordHasherTest {

    private final ConsolePasswordHasher passwordHasher = new ConsolePasswordHasher();

    @Test
    void shouldEncodeToArgon2id() {
        String encoded = passwordHasher.encode("admin123");

        assertThat(encoded).startsWith("$argon2id$");
        assertThat(passwordHasher.matches("admin123", encoded)).isTrue();
    }

    @Test
    void shouldMatchLegacyPbkdf2Hash() {
        String legacy = "pbkdf2_sha256$120000$ABEiM0RVZneImaq7zN3u/w==$SDdcSBs/sQioqO6CmSkLP+TzSWRrT5585nSe9kXNV2A=";

        assertThat(passwordHasher.matches("admin123", legacy)).isTrue();
    }
}
