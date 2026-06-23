package io.github.pinpols.batch.console.domain.job.application;

import io.github.pinpols.batch.console.domain.job.web.request.JobBundleCreateRequest;
import io.github.pinpols.batch.console.domain.job.web.request.JobBundleImportRequest;
import java.util.Map;

public interface ConsoleJobBundleApplicationService {

  Map<String, Object> exportBundle(String tenantId, String jobCode);

  Map<String, Object> create(JobBundleCreateRequest request);

  Map<String, Object> importBundle(JobBundleImportRequest request);
}
