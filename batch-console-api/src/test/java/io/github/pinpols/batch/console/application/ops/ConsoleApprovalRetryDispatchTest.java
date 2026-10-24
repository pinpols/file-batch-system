package io.github.pinpols.batch.console.application.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileApplicationService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobApprovalService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobRecoveryService;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("任务与分区重放审批按冻结目标派发，非法目标不标记执行完成")
class ConsoleApprovalRetryDispatchTest {

  @Mock
  private OrchestratorInternalRestClient orchestrator;
  @Mock
  private ConsoleRequestMetadataResolver metadata;
  @Mock
  private ConsoleJobRecoveryService recovery;
  @Mock
  private ConsoleJobApprovalService jobApproval;
  @Mock
  private ConsoleFileApplicationService files;
  @InjectMocks
  private DefaultConsoleApprovalApplicationService service;

  @ParameterizedTest
  @ValueSource(strings = {"JOB_TASK", "JOB_PARTITION"})
  @DisplayName("批准的 RETRY 动作派发到原任务或分区，并在业务成功后标记执行完成")
  void shouldDispatchFrozenRetryTarget_whenApprovalWasGranted(String targetType) {
    String idField = "JOB_TASK".equals(targetType) ? "taskId" : "partitionId";
    stubApproval(targetType, Map.of("tenantId", "t1", idField, 41L));
    if ("JOB_TASK".equals(targetType)) {
      when(recovery.replayTask(any(), eq("APR-RETRY"))).thenReturn("replayed");
    } else {
      when(recovery.replayPartition(any(), eq("APR-RETRY"))).thenReturn("replayed");
    }

    assertThat(service.approve("t1", "APR-RETRY", "checker", "approved selected target"))
        .isEqualTo("replayed");
    if ("JOB_TASK".equals(targetType)) {
      verify(recovery).replayTask(argThat(request -> request.getTaskId().equals(41L)
          && "APR-RETRY".equals(request.getApprovalId())), eq("APR-RETRY"));
      verify(recovery, never()).replayPartition(any(), any());
    } else {
      verify(recovery).replayPartition(argThat(request -> request.getPartitionId().equals(41L)
          && "APR-RETRY".equals(request.getApprovalId())), eq("APR-RETRY"));
      verify(recovery, never()).replayTask(any(), any());
    }
    verify(orchestrator).markApprovalExecuted("t1", "APR-RETRY");
  }

  @Test
  @DisplayName("未支持的 RETRY 目标拒绝执行，不改写审批为已执行")
  void shouldRejectUnsupportedTarget_whenRetryApprovalDoesNotNameTaskOrPartition() {
    stubApproval("JOB_INSTANCE", Map.of("tenantId", "t1", "targetId", 41L));

    assertThatThrownBy(() -> service.approve("t1", "APR-RETRY", "checker", "invalid target"))
        .isInstanceOfSatisfying(BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
    verify(recovery, never()).replayTask(any(), any());
    verify(recovery, never()).replayPartition(any(), any());
    verify(orchestrator, never()).markApprovalExecuted(any(), any());
  }

  private void stubApproval(String targetType, Map<String, Object> payload) {
    String record = JsonUtils.toJson(Map.of("record", Map.of(
        "approvalStatus", "APPROVED", "actionType", "RETRY", "targetType", targetType,
        "targetId", "41", "payloadJson", JsonUtils.toJson(payload))));
    when(orchestrator.loadApproval(eq("t1"), eq("APR-RETRY"), any())).thenAnswer(invocation -> {
      Class<?> type = invocation.getArgument(2);
      return JsonUtils.fromJsonStrict(record, type);
    });
  }
}
