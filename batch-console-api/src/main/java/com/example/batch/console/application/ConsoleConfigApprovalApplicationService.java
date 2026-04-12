package com.example.batch.console.application;

import com.example.batch.console.web.request.ConfigApprovalActionRequest;
import com.example.batch.console.web.request.ConfigReleaseApprovalSubmitRequest;
import java.util.Map;

public interface ConsoleConfigApprovalApplicationService {

  Map<String, Object> submit(Long releaseId, ConfigReleaseApprovalSubmitRequest request);

  Map<String, Object> detail(String tenantId, Long releaseId);

  Map<String, Object> approve(Long approvalId, ConfigApprovalActionRequest request);

  Map<String, Object> reject(Long approvalId, ConfigApprovalActionRequest request);
}
