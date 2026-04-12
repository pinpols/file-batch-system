package com.example.batch.console.application;

import com.example.batch.console.web.request.ConfigSyncExportRequest;
import com.example.batch.console.web.request.ConfigSyncImportRequest;
import com.example.batch.console.web.request.ConfigSyncPreviewRequest;
import java.util.List;
import java.util.Map;

public interface ConsoleConfigSyncApplicationService {

  Map<String, Object> export(ConfigSyncExportRequest request);

  Map<String, Object> preview(ConfigSyncPreviewRequest request);

  Map<String, Object> importBundle(ConfigSyncImportRequest request);

  List<Map<String, Object>> logs(String tenantId, int limit);
}
