package com.example.batch.worker.imports.plugin;

import com.example.batch.common.plugin.ImportLoadPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ImportLoadPluginRegistry {

  private final Map<String, ImportLoadPlugin> byId;

  public ImportLoadPluginRegistry(List<ImportLoadPlugin> plugins) {
    Map<String, ImportLoadPlugin> resolved = new LinkedHashMap<>();
    for (ImportLoadPlugin plugin : plugins) {
      String id = plugin.id().toLowerCase(Locale.ROOT);
      ImportLoadPlugin previous = resolved.putIfAbsent(id, plugin);
      if (previous != null) {
        throw new IllegalStateException(
            "duplicate ImportLoadPlugin id: "
                + id
                + " ("
                + previous.getClass().getName()
                + ", "
                + plugin.getClass().getName()
                + ")");
      }
    }
    this.byId = Map.copyOf(resolved);
  }

  public ImportLoadPlugin require(String id) {
    if (id == null || id.isBlank()) {
      id = WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED;
    }
    ImportLoadPlugin plugin = byId.get(id.toLowerCase(Locale.ROOT));
    if (plugin == null) {
      throw new IllegalStateException("no ImportLoadPlugin registered for id: " + id);
    }
    return plugin;
  }
}
