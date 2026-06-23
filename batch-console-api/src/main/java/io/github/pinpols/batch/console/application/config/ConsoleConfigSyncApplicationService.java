package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.console.web.request.config.ConfigSyncExportRequest;
import io.github.pinpols.batch.console.web.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.web.request.config.ConfigSyncPreviewRequest;
import java.util.List;
import java.util.Map;

public interface ConsoleConfigSyncApplicationService {

  Map<String, Object> export(ConfigSyncExportRequest request);

  Map<String, Object> preview(ConfigSyncPreviewRequest request);

  Map<String, Object> importBundle(ConfigSyncImportRequest request);

  List<Map<String, Object>> logs(String tenantId, int limit);
}
