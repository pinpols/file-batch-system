package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncExportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncPreviewRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncExportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncImportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncLogResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncPreviewResponse;

public interface ConsoleConfigSyncApplicationService {

  ConfigSyncExportResponse export(ConfigSyncExportRequest request);

  ConfigSyncPreviewResponse preview(ConfigSyncPreviewRequest request);

  ConfigSyncImportResponse importBundle(ConfigSyncImportRequest request);

  ConfigSyncLogResponse reconcile(String tenantId, Long logId);

  PageResponse<ConfigSyncLogResponse> logs(String tenantId, int pageNo, int pageSize);
}
