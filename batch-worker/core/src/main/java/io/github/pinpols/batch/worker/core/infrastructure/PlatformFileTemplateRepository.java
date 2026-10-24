package io.github.pinpols.batch.worker.core.infrastructure;

import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.TENANT_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.params;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.stringValue;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toInteger;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toLong;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toMap;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** Pipeline、模板及渠道定义的数据访问协作者。 */
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
