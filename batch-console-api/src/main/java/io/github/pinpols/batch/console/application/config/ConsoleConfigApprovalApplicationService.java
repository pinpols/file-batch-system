package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.console.web.request.config.ConfigApprovalActionRequest;
import io.github.pinpols.batch.console.web.request.config.ConfigReleaseApprovalSubmitRequest;
import java.util.Map;

public interface ConsoleConfigApprovalApplicationService {

  Map<String, Object> submit(Long releaseId, ConfigReleaseApprovalSubmitRequest request);

  Map<String, Object> detail(String tenantId, Long releaseId);

  Map<String, Object> approve(Long approvalId, ConfigApprovalActionRequest request);

  Map<String, Object> reject(Long approvalId, ConfigApprovalActionRequest request);
}
