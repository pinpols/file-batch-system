package io.github.pinpols.batch.worker.core.infrastructure;

import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.TENANT_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.params;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 文件模板配置的数据访问协作者。 */
@Repository
@RequiredArgsConstructor
public class PlatformFileTemplateRepository {

  private final PlatformFileRuntimeMapper mapper;

  public Map<String, Object> loadLatestTemplateConfig(
      String tenantId, String templateCode, String templateType) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(templateCode)) {
      return Map.of();
    }
    Map<String, Object> config = mapper.selectLatestTemplateConfig(
        params(TENANT_ID, tenantId, "templateCode", templateCode, "templateType", templateType));
    return config == null ? Map.of() : config;
  }
}
