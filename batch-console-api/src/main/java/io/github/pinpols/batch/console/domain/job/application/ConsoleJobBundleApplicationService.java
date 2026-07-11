package io.github.pinpols.batch.console.domain.job.application;

import io.github.pinpols.batch.console.domain.job.web.request.JobBundleCreateRequest;
import io.github.pinpols.batch.console.domain.job.web.request.JobBundleImportRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobBundleExportResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobBundleResultResponse;

public interface ConsoleJobBundleApplicationService {

  ConsoleJobBundleExportResponse exportBundle(String tenantId, String jobCode);

  ConsoleJobBundleResultResponse create(JobBundleCreateRequest request);

  ConsoleJobBundleResultResponse importBundle(JobBundleImportRequest request);
}
