package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("手动重放用稳定去重键提交并在成功后返回操作号")
class ManualReplayApplicationServiceTest {

  @Mock
  private RetryGovernanceService retryService;

  @Test
  @DisplayName("任务和分区保持各自的稳定重放键")
  void shouldUseStableReplayKeys() {
    ManualReplayApplicationService service = new ManualReplayApplicationService(retryService);
    assertThat(service.replayTask("t1", 5L)).startsWith("rpl");
    assertThat(service.replayPartition("t1", 6L)).startsWith("rpl");
    verify(retryService).retryTask("t1", 5L, "t1:replay-task:5");
    verify(retryService).retryPartition("t1", 6L, "t1:replay-partition:6");
  }

  @Test
  @DisplayName("重排失败向调用方传播，不返回操作号")
  void shouldPropagateRetryFailure() {
    ManualReplayApplicationService service = new ManualReplayApplicationService(retryService);
    doThrow(new IllegalStateException("failed"))
        .when(retryService)
        .retryTask("t1", 5L, "t1:replay-task:5");
    assertThatThrownBy(() -> service.replayTask("t1", 5L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("failed");
  }
}
