package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.CompensationCommandStatus;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskAssignmentService;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.CompensationCommandEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.ApprovalCommandMapper;
import io.github.pinpols.batch.orchestrator.mapper.CompensationCommandMapper;
import io.github.pinpols.batch.orchestrator.mapper.DeadLetterTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * WR-04 候选复现尝试（只加测试，不改主代码）：非 DISPATCH 的 UNKNOWN 恢复授权是否缺少绑定根请求的一次性消费。
 *
 * <p>命题：一份「已核实未执行」的恢复授权（同一 {@code approvalId}）被根请求 R1 消费后，另一个无关根请求 R2 是否还能拿同一
 * 授权放行并产生外部动作。
 *
 * <p>预期（正确行为）：审批绑定按请求载荷 SHA-256 指纹核对，R2 的载荷与 R1 不同 → 必须拒绝。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WR-04 候选: 已批准的一次性恢复授权不能被第二个无关根请求复用")
class ApprovedRecoveryAuthorizationReuseReproTest {

  @Mock
  private CompensationCommandMapper compensationCommandMapper;

  @Mock
  private ApprovalCommandMapper approvalCommandMapper;

  @Mock
  private DeadLetterTaskMapper deadLetterTaskMapper;

  @Mock
  private OrchestratorJobMappers jobMappers;

  @Mock
  private RetryGovernanceService retryGovernanceService;

  @Mock
  private FileGovernanceService fileGovernanceService;

  @Mock
  private DomainEventPublisher domainEventPublisher;

  @Mock
  private OrchestratorConfigCacheService configCacheService;

  @Mock
  private TaskAssignmentService taskAssignmentService;

  @Mock
  private CompensationTransactionExecutor transactionExecutor;

  @Mock
  private RelatedFileReferenceValidator relatedFileReferenceValidator;

  @Mock
  private WorkflowNodeReplayTargetResolver workflowNodeReplayTargetResolver;

  @Mock
  private ReplayOperationMapper replayOperationMapper;

  @InjectMocks
  private DefaultCompensationService compensationService;

  @Test
  @DisplayName("同一 approvalId + 另一个无关根目标 → 必须拒绝且不放行外部动作")
  void shouldRejectUnrelatedRootReusingApprovedRecoveryAuthorization() {
    CompensationCommandEntity authorizationConsumedByRootR1 = new CompensationCommandEntity();
    authorizationConsumedByRootR1.setTenantId("tenant-wr04");
    authorizationConsumedByRootR1.setCommandNo("cmp-root-r1");
    authorizationConsumedByRootR1.setApprovalId("approval-wr04");
    authorizationConsumedByRootR1.setRequestFingerprint("sha256-of-root-r1-payload");
    authorizationConsumedByRootR1.setCommandStatus(CompensationCommandStatus.SUCCESS.code());
    when(compensationCommandMapper.selectByTenantAndApprovalId("tenant-wr04", "approval-wr04"))
        .thenReturn(authorizationConsumedByRootR1);

    // 非 DISPATCH 作业（EXPORT）的另一个无关根请求，复用同一 approvalId
    CompensationSubmitCommand unrelatedRootR2 = CompensationSubmitCommand.builder()
        .tenantId("tenant-wr04")
        .compensationType("JOB")
        .targetId(999L)
        .jobCode("TA_EXPORT_REPORT")
        .bizDate(LocalDate.parse("2026-10-24"))
        .reason("reuse approved recovery authorization for another root")
        .operatorId("operator-2")
        .approvalId("approval-wr04")
        .traceId("trace-root-r2")
        .build();

    assertThatThrownBy(() -> compensationService.submit(unrelatedRootR2))
        .as("若这里没有抛错或返回原 commandNo，说明另一无关根复用了同一一次性授权（WR-04 复现）")
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.compensation.approval_request_mismatch");
  }
}
