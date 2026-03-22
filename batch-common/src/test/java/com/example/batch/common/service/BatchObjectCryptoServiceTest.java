package com.example.batch.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.config.BatchKmsProperties;
import com.example.batch.common.config.BatchSecurityProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BatchObjectCryptoService} — validates AES-GCM encrypt/decrypt
 * round-trip including the BATCHENC magic-header format used by StoreStep (export) and
 * the PreprocessStep KMS closure (import).
 */
class BatchObjectCryptoServiceTest {

    // 32-byte AES-256 test key (random, base64-encoded)
    private static final String KEY_REF = "TEST_KEY_2026";
    private static final String KEY_B64 = Base64.getEncoder().encodeToString(
            "01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII));

    private BatchObjectCryptoService cryptoService;

    @BeforeEach
    void setUp() {
        BatchSecurityProperties security = new BatchSecurityProperties();
        security.setTestingOpen(false);

        BatchKmsProperties kms = new BatchKmsProperties();
        kms.setDefaultKeyRef(KEY_REF);
        kms.setKeys(Map.of(KEY_REF, KEY_B64));

        cryptoService = new BatchObjectCryptoService(security, kms);
    }

    // ── byte[] round-trip ──────────────────────────────────────────────────────

    @Test
    void encryptAndDecryptBytes_shouldRoundTrip() {
        byte[] plaintext = "Hello, BATCHENC!".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = cryptoService.encrypt(plaintext, KEY_REF);
        byte[] recovered = cryptoService.decrypt(ciphertext);

        assertThat(recovered).isEqualTo(plaintext);
    }

    @Test
    void encryptedBytes_shouldStartWithMagicHeader() {
        byte[] ciphertext = cryptoService.encrypt("test".getBytes(StandardCharsets.UTF_8), KEY_REF);

        // BATCHENC magic (8 bytes)
        assertThat(new String(ciphertext, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("BATCHENC");
    }

    @Test
    void decryptUnencryptedBytes_shouldReturnOriginal() {
        byte[] plain = "not encrypted".getBytes(StandardCharsets.UTF_8);

        byte[] result = cryptoService.decrypt(plain);

        assertThat(result).isEqualTo(plain);
    }

    @Test
    void decryptNullBytes_shouldReturnNull() {
        assertThat(cryptoService.decrypt(null)).isNull();
    }

    @Test
    void decryptEmptyBytes_shouldReturnEmpty() {
        assertThat(cryptoService.decrypt(new byte[0])).isEmpty();
    }

    @Test
    void encryptWithDifferentCalls_shouldProduceDifferentCiphertext() {
        byte[] plaintext = "same plaintext".getBytes(StandardCharsets.UTF_8);

        byte[] cipher1 = cryptoService.encrypt(plaintext, KEY_REF);
        byte[] cipher2 = cryptoService.encrypt(plaintext, KEY_REF);

        // Different IVs per call → different ciphertext
        assertThat(cipher1).isNotEqualTo(cipher2);
        // Both decrypt to same plaintext
        assertThat(cryptoService.decrypt(cipher1)).isEqualTo(plaintext);
        assertThat(cryptoService.decrypt(cipher2)).isEqualTo(plaintext);
    }

    // ── stream round-trip ──────────────────────────────────────────────────────

    @Test
    void encryptStream_thenDecryptStream_shouldRoundTrip() throws Exception {
        byte[] plaintext = "streaming content".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream encOut = new ByteArrayOutputStream();
        cryptoService.encrypt(new ByteArrayInputStream(plaintext), encOut, KEY_REF);

        byte[] encrypted = encOut.toByteArray();
        try (InputStream decryptedStream = cryptoService.decryptIfNeeded(new ByteArrayInputStream(encrypted))) {
            byte[] recovered = decryptedStream.readAllBytes();
            assertThat(recovered).isEqualTo(plaintext);
        }
    }

    @Test
    void decryptIfNeeded_onPlainStream_shouldPassThrough() throws Exception {
        byte[] plain = "plain data".getBytes(StandardCharsets.UTF_8);

        try (InputStream result = cryptoService.decryptIfNeeded(new ByteArrayInputStream(plain))) {
            assertThat(result.readAllBytes()).isEqualTo(plain);
        }
    }

    // ── file round-trip ────────────────────────────────────────────────────────

    @Test
    void encryptFile_thenDecryptBytes_shouldRoundTrip() throws Exception {
        byte[] content = "file content for KMS test".getBytes(StandardCharsets.UTF_8);
        Path source = Files.createTempFile("kms-test-src-", ".txt");
        Path encrypted = Files.createTempFile("kms-test-enc-", ".bin");
        try {
            Files.write(source, content);

            cryptoService.encrypt(source, encrypted, KEY_REF);

            byte[] encryptedBytes = Files.readAllBytes(encrypted);
            byte[] decrypted = cryptoService.decrypt(encryptedBytes);
            assertThat(decrypted).isEqualTo(content);
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(encrypted);
        }
    }

    // ── shouldEncrypt / resolveKeyRef ──────────────────────────────────────────

    @Test
    void shouldEncrypt_whenFlagTrueAndTestingNotOpen_returnsTrue() {
        assertThat(cryptoService.shouldEncrypt(
                Map.of("content_encryption_enabled", Boolean.TRUE))).isTrue();
    }

    @Test
    void shouldEncrypt_whenFlagFalse_returnsFalse() {
        assertThat(cryptoService.shouldEncrypt(
                Map.of("content_encryption_enabled", Boolean.FALSE))).isFalse();
    }

    @Test
    void shouldEncrypt_whenTestingOpen_returnsFalse() {
        BatchSecurityProperties openSecurity = new BatchSecurityProperties();
        openSecurity.setTestingOpen(true);
        BatchKmsProperties kms = new BatchKmsProperties();
        kms.setDefaultKeyRef(KEY_REF);
        kms.setKeys(Map.of(KEY_REF, KEY_B64));
        BatchObjectCryptoService openService = new BatchObjectCryptoService(openSecurity, kms);

        assertThat(openService.shouldEncrypt(
                Map.of("content_encryption_enabled", Boolean.TRUE))).isFalse();
    }

    @Test
    void resolveKeyRef_usesSecurityMapKeyRef_whenPresent() {
        String ref = cryptoService.resolveKeyRef(Map.of("encryption_key_ref", KEY_REF));
        assertThat(ref).isEqualTo(KEY_REF);
    }

    @Test
    void resolveKeyRef_fallsBackToDefault_whenSecurityMapEmpty() {
        String ref = cryptoService.resolveKeyRef(Map.of());
        assertThat(ref).isEqualTo(KEY_REF);
    }

    @Test
    void encrypt_withMissingKeyMaterial_shouldThrow() {
        assertThatThrownBy(() -> cryptoService.encrypt(
                "test".getBytes(StandardCharsets.UTF_8), "NONEXISTENT_KEY"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing kms key material");
    }
}
