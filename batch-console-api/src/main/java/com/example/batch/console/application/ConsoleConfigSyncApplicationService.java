package com.example.batch.console.application;

import com.example.batch.console.web.request.config.ConfigSyncExportRequest;
import com.example.batch.console.web.request.config.ConfigSyncImportRequest;
import com.example.batch.console.web.request.config.ConfigSyncPreviewRequest;
import java.util.List;
import java.util.Map;

public interface ConsoleConfigSyncApplicationService {

  Map<String, Object> export(ConfigSyncExportRequest request);

  Map<String, Object> preview(ConfigSyncPreviewRequest request);

  Map<String, Object> importBundle(ConfigSyncImportRequest request);

  List<Map<String, Object>> logs(String tenantId, int limit);
}
