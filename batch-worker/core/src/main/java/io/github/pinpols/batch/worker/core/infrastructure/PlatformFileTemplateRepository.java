package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileTemplateConfigView;
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
    PlatformFileTemplateConfigView config =
        mapper.selectLatestTemplateConfig(tenantId, templateCode, templateType);
    return EmptyChecks.isNull(config) ? Map.of() : config.toPluginValues();
  }
}
