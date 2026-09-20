package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.rbac.entity.SecretVersionEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.SecretVersionMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class SecretPayloadMigrationRunnerTest {

  @Test
  void acceptsConcurrentMigrationWhenNoUnprotectedRowsRemain() {
    SecretVersionMapper mapper = mock(SecretVersionMapper.class);
    SecretPayloadProtector protector = mock(SecretPayloadProtector.class);
    SecretVersionEntity candidate = new SecretVersionEntity();
    candidate.setId(1L);
    candidate.setTenantId("t1");
    candidate.setSecretPayload("{\"token\":\"legacy\"}");
    when(mapper.selectUnprotectedPayloads(anyInt()))
        .thenReturn(List.of(candidate))
        .thenReturn(List.of());
    when(mapper.updateProtectedPayload(anyMap())).thenReturn(0);
    when(protector.protect(candidate.getSecretPayload()))
        .thenReturn("{\"format\":\"BATCHENC_BASE64_V1\"}");

    SecretPayloadMigrationRunner runner = new SecretPayloadMigrationRunner(mapper, protector);

    assertThatCode(() -> runner.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
  }
}
