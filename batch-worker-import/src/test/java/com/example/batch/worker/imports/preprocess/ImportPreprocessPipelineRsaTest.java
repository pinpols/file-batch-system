package com.example.batch.worker.imports.preprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code VERIFY_RSA_SHA256} preprocess step using the test RSA keys
 * at {@code fixtures/test-rsa-public.pem} and {@code fixtures/test-rsa-private.pem}.
 *
 * <p>Key generation (one-time, already done):
 * <pre>
 *   openssl genrsa -out test-rsa-private.pem 2048
 *   openssl rsa -in test-rsa-private.pem -pubout -out test-rsa-public.pem
 * </pre>
 */
class ImportPreprocessPipelineRsaTest {

    private static final byte[] PAYLOAD = "customerNo,customerName\nC001,Alice Wang\n"
            .getBytes(StandardCharsets.UTF_8);

    private static String publicKeyPem;
    private static String validSignatureB64;

    @BeforeAll
    static void loadKeys() throws Exception {
        publicKeyPem = loadResource("fixtures/test-rsa-public.pem");

        // Sign the test payload with the PKCS#8 private key (fresh signature each test run)
        String privateKeyPem = loadResource("fixtures/test-rsa-private-pkcs8.pem");
        PrivateKey privateKey = parsePkcs8PrivateKey(privateKeyPem);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(PAYLOAD);
        validSignatureB64 = Base64.getEncoder().encodeToString(signer.sign());
    }

    // ── valid signature passes ─────────────────────────────────────────────────

    @Test
    void shouldPassVerification_withValidRsaSignature() {
        Map<String, Object> step = Map.of(
                "type", "VERIFY_RSA_SHA256",
                "publicKeyPem", publicKeyPem,
                "signatureBase64", validSignatureB64
        );
        Map<String, Object> template = Map.of(
                "preprocess_pipeline", java.util.List.of(step)
        );

        byte[] result = ImportPreprocessPipeline.run(PAYLOAD, null, template);

        assertThat(result).isEqualTo(PAYLOAD);
    }

    // ── tampered payload rejected ──────────────────────────────────────────────

    @Test
    void shouldFailVerification_whenPayloadTampered() {
        byte[] tamperedPayload = "customerNo,customerName\nC001,Eve (hacker)\n"
                .getBytes(StandardCharsets.UTF_8);

        Map<String, Object> step = Map.of(
                "type", "VERIFY_RSA_SHA256",
                "publicKeyPem", publicKeyPem,
                "signatureBase64", validSignatureB64
        );
        Map<String, Object> template = Map.of(
                "preprocess_pipeline", java.util.List.of(step)
        );

        assertThatThrownBy(() -> ImportPreprocessPipeline.run(tamperedPayload, null, template))
                .isInstanceOf(ImportPreprocessException.class)
                .hasMessageContaining("RSA");
    }

    // ── missing public key config rejected ────────────────────────────────────

    @Test
    void shouldFail_whenPublicKeyPemMissing() {
        Map<String, Object> step = Map.of(
                "type", "VERIFY_RSA_SHA256",
                "signatureBase64", validSignatureB64
                // no publicKeyPem
        );
        Map<String, Object> template = Map.of(
                "preprocess_pipeline", java.util.List.of(step)
        );

        assertThatThrownBy(() -> ImportPreprocessPipeline.run(PAYLOAD, null, template))
                .isInstanceOf(ImportPreprocessException.class)
                .hasMessageContaining("publicKeyPem");
    }

    // ── signature supplied via payload metadata ────────────────────────────────

    @Test
    void shouldPassVerification_signatureViaPayloadMetadata() {
        com.example.batch.worker.imports.domain.ImportPayload payload =
                new com.example.batch.worker.imports.domain.ImportPayload(
                        null, null, null, null, "JSON", null, null, null, null,
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null,
                        Map.of("signatureBase64", validSignatureB64)
                );

        Map<String, Object> step = Map.of(
                "type", "VERIFY_RSA_SHA256",
                "publicKeyPem", publicKeyPem
                // signatureBase64 not in step — provided via metadata
        );
        Map<String, Object> template = Map.of(
                "preprocess_pipeline", java.util.List.of(step)
        );

        byte[] result = ImportPreprocessPipeline.run(PAYLOAD, payload, template);

        assertThat(result).isEqualTo(PAYLOAD);
    }

    // ── testingOpen bypasses RSA check ─────────────────────────────────────────

    @Test
    void shouldBypassRsaVerification_whenTestingOpen() {
        Map<String, Object> step = Map.of(
                "type", "VERIFY_RSA_SHA256",
                "publicKeyPem", publicKeyPem,
                "signatureBase64", "INVALID_BASE64_GARBAGE"
        );
        Map<String, Object> template = Map.of(
                "preprocess_pipeline", java.util.List.of(step)
        );

        // testingOpen=true skips the RSA check entirely
        byte[] result = ImportPreprocessPipeline.run(PAYLOAD, null, template, true);
        assertThat(result).isEqualTo(PAYLOAD);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String loadResource(String resourcePath) throws Exception {
        URL url = ImportPreprocessPipelineRsaTest.class.getClassLoader().getResource(resourcePath);
        assertThat(url).as("Resource not found: %s", resourcePath).isNotNull();
        return Files.readString(Path.of(url.getPath()));
    }

    /**
     * Parse a PKCS#8 PEM-encoded RSA private key.
     * Key generated with:
     *   openssl genrsa -out test-rsa-private.pem 2048
     *   openssl pkcs8 -topk8 -nocrypt -in test-rsa-private.pem -out test-rsa-private-pkcs8.pem
     */
    private static PrivateKey parsePkcs8PrivateKey(String pem) throws Exception {
        String base64 = pem
                .replaceAll("-----BEGIN PRIVATE KEY-----", "")
                .replaceAll("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
