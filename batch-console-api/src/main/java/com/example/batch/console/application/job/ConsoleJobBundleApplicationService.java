package com.example.batch.console.application.job;

import com.example.batch.console.web.request.job.JobBundleCreateRequest;
import com.example.batch.console.web.request.job.JobBundleImportRequest;
import java.util.Map;

public interface ConsoleJobBundleApplicationService {

  Map<String, Object> exportBundle(String tenantId, String jobCode);

  Map<String, Object> create(JobBundleCreateRequest request);

  Map<String, Object> importBundle(JobBundleImportRequest request);
}
