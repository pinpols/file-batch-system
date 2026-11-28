package io.github.pinpols.batch.worker.core.infrastructure;

import java.util.Map;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileTemplateConfigView;

/** 文件模板配置的数据访问协作者。 */
@Repository
@RequiredArgsConstructor
public class PlatformFileTemplateRepository {

    private final PlatformFileRuntimeMapper mapper;

    public Map<String, Object> loadLatestTemplateConfig(String tenantId, String templateCode, String templateType) {
        if (!EmptyValues.hasText(tenantId) || !EmptyValues.hasText(templateCode)) {
            return Map.of();
        }
        PlatformFileTemplateConfigView config = mapper.selectLatestTemplateConfig(tenantId, templateCode, templateType);
        return config == null ? Map.of() : config.toPluginValues();
    }
}
