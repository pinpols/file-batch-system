package com.example.batch.orchestrator.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.service.governance.DefaultRetryGovernanceService;
import com.example.batch.orchestrator.config.RetryGovernanceProperties;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.DeadLetterTaskEntity;
import com.example.batch.orchestrator.mapper.DeadLetterTaskMapper;
import com.example.batch.orchestrator.mapper.JobDefinitionMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.RetryScheduleMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V90 验证：autoRetryDueDeadLetters 调度行为。
 *
 * <ul>
 *   <li>到期 + 未达 max → 调用 replayDeadLetter（成功路径）
 *   <li>到达 max（依赖入口边界保护）→ 转 GIVE_UP
 *   <li>replay 抛异常 + 已达 max → 转 GIVE_UP
 *   <li>replay 抛异常 + 未达 max → markReplayFailure 内部处理 backoff，scheduler 不额外动 status
 *   <li>BUSINESS / max=0 不会被 selectDueAutoRetries 选出（mapper 行为，依靠 SQL；这里只测 service 边界）
 * </ul>
 */
class DeadLetterAutoRetryTest {

  private DeadLetterTaskMapper deadLetterTaskMapper;
  private DefaultRetryGovernanceService service;

  @BeforeEach
  void setUp() {
    RetryScheduleMapper retryScheduleMapper = mock(RetryScheduleMapper.class);
    deadLetterTaskMapper = mock(DeadLetterTaskMapper.class);
    JobDefinitionMapper jobDefinitionMapper = mock(JobDefinitionMapper.class);
    JobTaskMapper jobTaskMapper = mock(JobTaskMapper.class);
    JobPartitionMapper jobPartitionMapper = mock(JobPartitionMapper.class);
    JobInstanceMapper jobInstanceMapper = mock(JobInstanceMapper.class);
    JobStepInstanceMapper jobStepInstanceMapper = mock(JobStepInstanceMapper.class);
    TaskDispatchOutboxService taskDispatchOutboxService = mock(TaskDispatchOutboxService.class);
    RetryGovernanceProperties properties = new RetryGovernanceProperties();
    properties.setBatchSize(50);
    properties.setDefaultMaxRetryCount(3);
    properties.setFixedDelaySeconds(60L);
    BatchOrchestratorGovernanceProperties governance =
        mock(BatchOrchestratorGovernanceProperties.class);
    when(governance.retry()).thenReturn(properties);

    service =
        new DefaultRetryGovernanceService(
            retryScheduleMapper,
            deadLetterTaskMapper,
            jobDefinitionMapper,
            jobTaskMapper,
            jobPartitionMapper,
            jobInstanceMapper,
            jobStepInstanceMapper,
            taskDispatchOutboxService,
            governance,
            null /* jobExecutionLogMapper: audit 在本测试不覆盖 */);
  }

  /**
   * 入口边界保护：理论上 selectDueAutoRetries 的 SQL where 子句已过滤 replay_count &lt; max_replay_count， 但配置漂移 /
   * 数据迁移可能让 max=0 漏进来；scheduler 必须主动转 GIVE_UP, 不允许进入 replayDeadLetter 死循环.
   */
  @Test
  void recordWithMaxAlreadyReached_shouldGiveUpWithoutReplay() {
    DeadLetterTaskEntity dl = entity(7L, "tA", 3, 3);
    when(deadLetterTaskMapper.selectDueAutoRetries(anyInt())).thenReturn(List.of(dl));

    service.autoRetryDueDeadLetters();

    verify(deadLetterTaskMapper).markGiveUp("tA", 7L);
    verify(deadLetterTaskMapper, never()).selectById(anyString(), anyLong());
  }

  /** replay 抛异常 + 新 replayCount 已用尽预算 → 转 GIVE_UP（关闭后续自动重放）. */
  @Test
  void exhaustedAfterReplayFailure_shouldGiveUp() {
    DeadLetterTaskEntity dl = entity(8L, "tB", 2, 3); // 重放后会变 3 = max
    when(deadLetterTaskMapper.selectDueAutoRetries(anyInt())).thenReturn(List.of(dl));
    // replayDeadLetter 内部第一步 selectById：返回不存在，触发 IllegalStateException
    when(deadLetterTaskMapper.selectById("tB", 8L)).thenReturn(null);

    service.autoRetryDueDeadLetters();

    verify(deadLetterTaskMapper).markGiveUp("tB", 8L);
  }

  /** replay 抛异常但还有自动重放预算 → scheduler 不额外动 status，保留 markReplayFailure 内部已写入的 next_replay_at. */
  @Test
  void notExhausted_shouldKeepFailedStatusForBackoff() {
    DeadLetterTaskEntity dl = entity(9L, "tC", 0, 3); // 重放后会变 1 < 3
    when(deadLetterTaskMapper.selectDueAutoRetries(anyInt())).thenReturn(List.of(dl));
    when(deadLetterTaskMapper.selectById("tC", 9L)).thenReturn(null);

    service.autoRetryDueDeadLetters();

    verify(deadLetterTaskMapper, never()).markGiveUp(eq("tC"), eq(9L));
  }

  /** 一批 0 条 → 不调用任何下游. */
  @Test
  void emptyBatch_shouldDoNothing() {
    when(deadLetterTaskMapper.selectDueAutoRetries(anyInt())).thenReturn(List.of());

    service.autoRetryDueDeadLetters();

    verify(deadLetterTaskMapper, never()).markGiveUp(anyString(), anyLong());
    verify(deadLetterTaskMapper, never())
        .markReplayFailure(
            anyString(), anyLong(), anyString(), anyInt(), any(), anyString(), any());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static DeadLetterTaskEntity entity(Long id, String tenantId, int replayCount, int max) {
    DeadLetterTaskEntity dl = new DeadLetterTaskEntity();
    dl.setId(id);
    dl.setTenantId(tenantId);
    dl.setReplayStatus("FAILED");
    dl.setReplayCount(replayCount);
    dl.setMaxReplayCount(max);
    dl.setErrorClass("SYSTEM");
    dl.setSourceType("JOB_PARTITION");
    dl.setSourceId(100L);
    return dl;
  }
}
