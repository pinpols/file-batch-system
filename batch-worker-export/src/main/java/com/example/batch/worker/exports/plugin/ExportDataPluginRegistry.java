package com.example.batch.worker.exports.plugin;

import com.example.batch.common.plugin.ExportDataPlugin;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 导出数据插件注册中心，收集所有 {@link com.example.batch.common.plugin.ExportDataPlugin} Bean 并按 id 索引。
 */
@Component
public class ExportDataPluginRegistry {

    private final Map<String, ExportDataPlugin> byId;

    public ExportDataPluginRegistry(List<ExportDataPlugin> plugins) {
        Map<String, ExportDataPlugin> resolved = new LinkedHashMap<>();
        for (ExportDataPlugin plugin : plugins) {
            String id = plugin.id().toLowerCase(Locale.ROOT);
            ExportDataPlugin previous = resolved.putIfAbsent(id, plugin);
            if (previous != null) {
                throw new IllegalStateException("duplicate ExportDataPlugin id: " + id
                        + " (" + previous.getClass().getName() + ", " + plugin.getClass().getName() + ")");
            }
        }
        this.byId = Map.copyOf(resolved);
    }

    /**
     * 按 id 获取插件，不存在时抛出异常。
     *
     * @param id 插件标识（大小写不敏感）
     * @return 对应的 ExportDataPlugin
     * @throws IllegalStateException id 为空或未找到插件时抛出
     */
    public ExportDataPlugin require(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                    "export_data_ref is required in template config: use 'jdbc_mapped_export' or 'sql_template_export'");
        }
        ExportDataPlugin plugin = byId.get(id.toLowerCase(Locale.ROOT));
        if (plugin == null) {
            throw new IllegalStateException("no ExportDataPlugin registered for id: " + id);
        }
        return plugin;
    }
}
