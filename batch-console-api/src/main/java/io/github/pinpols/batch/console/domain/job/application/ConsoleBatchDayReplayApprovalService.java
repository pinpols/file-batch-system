package io.github.pinpols.batch.console.domain.job.application;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.contract.response.ConsoleReplayOperationResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayReplaySessionResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** 批量日重放审批：以认证主体作为审批人。 */
@Service
@RequiredArgsConstructor
public class ConsoleBatchDayReplayApprovalService {
  private final ConsoleOrchestratorPort orchestrator;

  public CommonResponse<ConsoleReplayOperationResponse> approveOperation(Long id, String tenantId) {
    return orchestrator.replayOperationApprove(id, tenantId, authenticatedApprover());
  }

  public CommonResponse<ConsoleBatchDayReplaySessionResponse> approveSession(Long id, String tenantId) {
    return orchestrator.batchDayReplayApprove(id, tenantId, authenticatedApprover());
  }

  private String authenticatedApprover() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof ConsolePrincipal principal)
        || !Texts.hasText(principal.username())) {
      throw BizException.of(ResultCode.UNAUTHORIZED, "error.auth.unauthenticated");
    }
    boolean admin = authentication.getAuthorities().stream()
        .anyMatch(authority -> ConsoleRoles.ADMIN.equals(authority.getAuthority()));
    if (!admin) {
      throw BizException.of(ResultCode.FORBIDDEN, ResultCode.FORBIDDEN.detailKey());
    }
    return principal.username();
  }
}
