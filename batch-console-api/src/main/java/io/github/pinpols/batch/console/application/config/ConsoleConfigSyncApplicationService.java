package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncExportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncPreviewRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncExportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncImportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncLogResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncPreviewResponse;
import java.util.List;

public interface ConsoleConfigSyncApplicationService {

  ConfigSyncExportResponse export(ConfigSyncExportRequest request);

  ConfigSyncPreviewResponse preview(ConfigSyncPreviewRequest request);

  ConfigSyncImportResponse importBundle(ConfigSyncImportRequest request);

  List<ConfigSyncLogResponse> logs(String tenantId, int limit);
}
