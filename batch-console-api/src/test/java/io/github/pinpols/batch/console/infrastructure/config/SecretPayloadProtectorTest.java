package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import org.junit.jupiter.api.Test;

class SecretPayloadProtectorTest {

  @Test
  void storesEncryptedEnvelopeWithoutPlaintext() {
    BatchObjectCryptoService cryptoService = mock(BatchObjectCryptoService.class);
    when(cryptoService.encrypt(any(byte[].class), isNull())).thenReturn(new byte[] {1, 2, 3, 4});
    SecretPayloadProtector protector = new SecretPayloadProtector(cryptoService);

    String protectedPayload = protector.protect("{\"password\":\"plain-secret\"}");

    assertThat(protectedPayload)
        .contains("BATCHENC_BASE64_V1")
        .contains("AQIDBA==")
        .doesNotContain("plain-secret");
  }
}
