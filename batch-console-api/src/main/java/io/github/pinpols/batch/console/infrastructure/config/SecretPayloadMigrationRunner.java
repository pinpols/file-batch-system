package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.rbac.entity.SecretVersionEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.SecretVersionMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 在控制台开始提供服务前，加密历史密钥载荷。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecretPayloadMigrationRunner implements ApplicationRunner {

  private static final int BATCH_SIZE = 100;

  private final SecretVersionMapper secretVersionMapper;
  private final SecretPayloadProtector secretPayloadProtector;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    int migrated = 0;
    while (true) {
      List<SecretVersionEntity> candidates =
          secretVersionMapper.selectUnprotectedPayloads(BATCH_SIZE);
      if (EmptyChecks.isEmpty(candidates)) {
        break;
      }
      int migratedInBatch = 0;
      for (SecretVersionEntity candidate : candidates) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", candidate.getId());
        params.put("tenantId", candidate.getTenantId());
        params.put(
            "secretPayloadJson", secretPayloadProtector.protect(candidate.getSecretPayload()));
        migratedInBatch += secretVersionMapper.updateProtectedPayload(params);
      }
      if (migratedInBatch == 0) {
        if (EmptyChecks.isEmpty(secretVersionMapper.selectUnprotectedPayloads(1))) {
          break;
        }
        throw new IllegalStateException("legacy secret payload encryption made no progress");
      }
      migrated += migratedInBatch;
    }
    if (migrated > 0) {
      log.info("legacy secret payload encryption completed: migratedCount={}", migrated);
    }
  }
}
