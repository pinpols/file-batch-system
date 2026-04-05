package com.example.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.exception.BizException;
import org.junit.jupiter.api.Test;

class ConsolePasswordHasherTest {

    /** 与 batch-orchestrator Flyway V34 控制台默认种子一致；明文为 admin123。 */
    static final String SEED_ARGON2_ADMIN123 =
            "$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s";

    private final ConsolePasswordHasher passwordHasher = new ConsolePasswordHasher();

    @Test
    void shouldEncodeToArgon2id() {
        String encoded = passwordHasher.encode("admin123");

        assertThat(encoded).startsWith("$argon2id$");
        assertThat(passwordHasher.matches("admin123", encoded)).isTrue();
    }

    @Test
    void shouldMatchFlywaySeedArgon2Hash() {
        assertThat(passwordHasher.matches("admin123", SEED_ARGON2_ADMIN123)).isTrue();
    }

    @Test
    void shouldRejectNonArgon2Hash() {
        assertThatThrownBy(() -> passwordHasher.matches("x", "pbkdf2_sha256$120000$salt$hash"))
                .isInstanceOf(BizException.class);
    }
}
