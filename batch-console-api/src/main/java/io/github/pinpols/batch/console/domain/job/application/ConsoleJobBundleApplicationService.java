package io.github.pinpols.batch.console.domain.job.application;

import io.github.pinpols.batch.console.domain.job.application.contract.request.JobBundleCreateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobBundleImportRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobBundleExportResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobBundleResultResponse;

public interface ConsoleJobBundleApplicationService {

  ConsoleJobBundleExportResponse exportBundle(String tenantId, String jobCode);

  ConsoleJobBundleResultResponse create(JobBundleCreateRequest request);

  ConsoleJobBundleResultResponse importBundle(JobBundleImportRequest request);
}
