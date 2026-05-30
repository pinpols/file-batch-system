package com.example.batch.console.domain.job.application;

import com.example.batch.console.domain.job.web.request.JobBundleCreateRequest;
import com.example.batch.console.domain.job.web.request.JobBundleImportRequest;
import java.util.Map;

public interface ConsoleJobBundleApplicationService {

  Map<String, Object> exportBundle(String tenantId, String jobCode);

  Map<String, Object> create(JobBundleCreateRequest request);

  Map<String, Object> importBundle(JobBundleImportRequest request);
}
