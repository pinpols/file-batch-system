package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleConfigApprovalDetailResponse;
import io.github.pinpols.batch.console.web.request.config.ConfigApprovalActionRequest;
import io.github.pinpols.batch.console.web.request.config.ConfigReleaseApprovalSubmitRequest;

public interface ConsoleConfigApprovalApplicationService {

  ConsoleConfigApprovalDetailResponse submit(
      Long releaseId, ConfigReleaseApprovalSubmitRequest request);

  ConsoleConfigApprovalDetailResponse detail(String tenantId, Long releaseId);

  ConsoleConfigApprovalDetailResponse approve(Long approvalId, ConfigApprovalActionRequest request);

  ConsoleConfigApprovalDetailResponse reject(Long approvalId, ConfigApprovalActionRequest request);
}
