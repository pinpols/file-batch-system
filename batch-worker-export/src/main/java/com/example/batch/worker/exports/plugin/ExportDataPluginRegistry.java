package com.example.batch.worker.exports.plugin;

import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

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

    public ExportDataPlugin require(String id) {
        if (id == null || id.isBlank()) {
            id = WorkerPluginIds.EXPORT_DATA_SETTLEMENT;
        }
        ExportDataPlugin plugin = byId.get(id.toLowerCase(Locale.ROOT));
        if (plugin == null) {
            throw new IllegalStateException("no ExportDataPlugin registered for id: " + id);
        }
        return plugin;
    }
}
