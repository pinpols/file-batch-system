package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.DeadLetterErrorClass;
import io.github.pinpols.batch.common.enums.RetryScheduleStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.governance.DeadLetterOrphanSourceException;
import io.github.pinpols.batch.orchestrator.application.service.governance.DefaultRetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryRequeueCoordinator;
import io.github.pinpols.batch.orchestrator.config.RetryGovernanceProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.DeadLetterTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.RetryScheduleEntity;
import io.github.pinpols.batch.orchestrator.mapper.DeadLetterTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.RetryScheduleMapper;
import io.github.pinpols.batch.testing.TestConstants.DeadLetter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@DisplayName("重试治理服务: 重试计划生成, 死信转投与死信重放口径")
class DefaultRetryGovernanceServiceTest {

  private RetryScheduleMapper retryScheduleMapper;
  private DeadLetterTaskMapper deadLetterTaskMapper;
  private JobDefinitionMapper jobDefinitionMapper;
  private JobTaskMapper jobTaskMapper;
  private JobPartitionMapper jobPartitionMapper;
  private JobInstanceMapper jobInstanceMapper;
  private JobStepInstanceMapper jobStepInstanceMapper;
  private TaskDispatchOutboxService taskDispatchOutboxService;
  private RetryGovernanceProperties properties;
  private BatchOrchestratorGovernanceProperties governance;
  private DefaultRetryGovernanceService service;

  @BeforeEach
  void setUp() {
    retryScheduleMapper = mock(RetryScheduleMapper.class);
    deadLetterTaskMapper = mock(DeadLetterTaskMapper.class);
    jobDefinitionMapper = mock(JobDefinitionMapper.class);
    jobTaskMapper = mock(JobTaskMapper.class);
    jobPartitionMapper = mock(JobPartitionMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    jobStepInstanceMapper = mock(JobStepInstanceMapper.class);
    taskDispatchOutboxService = mock(TaskDispatchOutboxService.class);
    properties = new RetryGovernanceProperties();
    properties.setFixedDelaySeconds(60L);
    properties.setExponentialMultiplier(2L);
    properties.setMaxDelaySeconds(3600L);
    properties.setDefaultMaxRetryCount(3);
    governance = mock(BatchOrchestratorGovernanceProperties.class);
    when(governance.retry()).thenReturn(properties);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

    RetryRequeueCoordinator retryRequeueCoordinator = new RetryRequeueCoordinator(
        retryScheduleMapper,
        jobTaskMapper,
        jobPartitionMapper,
        jobInstanceMapper,
        jobStepInstanceMapper,
        taskDispatchOutboxService);

    service = new DefaultRetryGovernanceService(
        retryScheduleMapper,
        deadLetterTaskMapper,
        jobDefinitionMapper,
        jobTaskMapper,
        retryRequeueCoordinator,
        governance,
        null /* jobExecutionLogMapper: audit 在本测试不覆盖 */,
        transactionManager);
  }

  // ── scheduleRetryIfNecessary — null guards ────────────────────────────────

  @Test
  @DisplayName("任务为空时不做重试调度, 返回未排程")
  void shouldReturnFalseWhenTaskIsNull() {
    boolean result =
        service.scheduleRetryIfNecessary(null, partition(1L, 0), jobInstance(1L), "ERR", "err");
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("分区为空时不做重试调度, 返回未排程")
  void shouldReturnFalseWhenPartitionIsNull() {
    boolean result =
        service.scheduleRetryIfNecessary(task("t1", 1L, 1L), null, jobInstance(1L), "ERR", "err");
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("作业实例为空时不做重试调度, 返回未排程")
  void shouldReturnFalseWhenJobInstanceIsNull() {
    boolean result =
        service.scheduleRetryIfNecessary(task("t1", 1L, 1L), partition(1L, 0), null, "ERR", "err");
    assertThat(result).isFalse();
  }

  // ── scheduleRetryIfNecessary — NONE policy → dead letter ─────────────────

  @Test
  @DisplayName("重试策略为不重试时直接生成死信, 不写重试计划")
  void shouldCreateDeadLetterWhenRetryPolicyIsNone() {
    when(jobDefinitionMapper.selectById(1L)).thenReturn(jobDefinitionWithPolicy(1L, "NONE", 3));

    boolean result = service.scheduleRetryIfNecessary(
        task("t1", 1L, 1L), partition(1L, 0), jobInstance(1L), "ERR", "none policy");

    assertThat(result).isFalse();
    verify(deadLetterTaskMapper).insert(any());
    verify(retryScheduleMapper, never()).insert(any());
  }

  @Test
  @DisplayName("最大重试次数为零时生成死信, 不写重试计划")
  void shouldCreateDeadLetterWhenMaxRetryCountZero() {
    when(jobDefinitionMapper.selectById(1L)).thenReturn(jobDefinitionWithPolicy(1L, "FIXED", 0));

    boolean result = service.scheduleRetryIfNecessary(
        task("t1", 1L, 1L), partition(1L, 0), jobInstance(1L), "ERR", "max zero");

    assertThat(result).isFalse();
    verify(deadLetterTaskMapper).insert(any());
  }

  // ── scheduleRetryIfNecessary — retry count exhausted ─────────────────────

  @Test
  @DisplayName("重试次数已达上限时生成死信, 不写重试计划")
  void shouldCreateDeadLetterWhenRetryCountExhausted() {
    when(jobDefinitionMapper.selectById(1L)).thenReturn(jobDefinitionWithPolicy(1L, "FIXED", 2));

    // partition already has 2 retries, max is 2 → exhausted
    boolean result = service.scheduleRetryIfNecessary(
        task("t1", 1L, 1L), partition(1L, 2), jobInstance(1L), "ERR", "exhausted");

    assertThat(result).isFalse();
    verify(deadLetterTaskMapper).insert(any());
    verify(retryScheduleMapper, never()).insert(any());
  }

  // ── scheduleRetryIfNecessary — schedule retry ─────────────────────────────

  @Test
  @DisplayName("生成重试计划时状态为等待, 次数递增并带上错误码与下次重试时间")
  void shouldInsertRetryScheduleWithCorrectStatusAndDedup() {
    when(jobDefinitionMapper.selectById(1L)).thenReturn(jobDefinitionWithPolicy(1L, "FIXED", 3));

    boolean result = service.scheduleRetryIfNecessary(
        task("t1", 1L, 1L), partition(1L, 0), jobInstance(1L), "PARSE_ERR", "parse failed");

    assertThat(result).isTrue();
    ArgumentCaptor<RetryScheduleEntity> captor = ArgumentCaptor.forClass(RetryScheduleEntity.class);
    verify(retryScheduleMapper).insert(captor.capture());
    RetryScheduleEntity schedule = captor.getValue();
    assertThat(schedule.getRetryStatus()).isEqualTo(RetryScheduleStatus.WAITING.code());
    assertThat(schedule.getRetryCount()).isEqualTo(1);
    assertThat(schedule.getLastErrorCode()).isEqualTo("PARSE_ERR");
    assertThat(schedule.getNextRetryAt()).isAfter(BatchDateTimeSupport.utcNow().minusSeconds(5));
  }

  // ── 永久性输入/数据错 → 不重试,直接 BUSINESS 死信(防永久失败无限重试洪水)──────────
  // 实测:IMPORT_PARSE_FAILED / LOAD_FAILED 漏出 NON_RETRYABLE 白名单 → 被判 SYSTEM →
  // 3 个畸形夹具任务在 dead_letter 无限循环(653 行 / replay_count 恒 1)。对照上面同 FIXED/3
  // 策略下可重试码会 insert retry_schedule,这里永久码必须 false + BUSINESS 死信 + 不排重试。
  @ParameterizedTest
  @DisplayName("导入类永久失败错误直接生成业务死信, 不排重试")
  @ValueSource(strings = {"IMPORT_PARSE_FAILED", "IMPORT_PARSE_EMPTY", "IMPORT_LOAD_FAILED"})
  void shouldDeadLetterAsBusinessForPermanentImportErrors(String errorCode) {
    when(jobDefinitionMapper.selectById(1L)).thenReturn(jobDefinitionWithPolicy(1L, "FIXED", 3));

    boolean result = service.scheduleRetryIfNecessary(
        task("t1", 1L, 1L), partition(1L, 0), jobInstance(1L), errorCode, "permanent input");

    assertThat(result).as(errorCode + " 永久失败,不应排重试").isFalse();
    ArgumentCaptor<DeadLetterTaskEntity> dl = ArgumentCaptor.forClass(DeadLetterTaskEntity.class);
    verify(deadLetterTaskMapper).insert(dl.capture());
    assertThat(dl.getValue().getErrorClass())
        .as(errorCode + " 应归类 BUSINESS(永久,不自动重试)")
        .isEqualTo(DeadLetterErrorClass.BUSINESS.code());
    verify(retryScheduleMapper, never()).insert(any());
  }

  @Test
  @DisplayName("作业定义不存在时使用默认重试策略并生成重试计划")
  void shouldUseDefaultRetryPolicyWhenJobDefinitionNotFound() {
    when(jobDefinitionMapper.selectById(anyLong())).thenReturn(null);

    boolean result = service.scheduleRetryIfNecessary(
        task("t1", 1L, 999L), partition(1L, 0), jobInstance(999L), "ERR", "msg");

    assertThat(result).isTrue();
    verify(retryScheduleMapper).insert(any());
  }

  @Test
  @DisplayName("作业定义标识为空时使用默认重试策略并生成重试计划")
  void shouldUseDefaultRetryPolicyWhenJobDefinitionIdIsNull() {
    JobInstanceEntity jobInst = jobInstance(null);
    boolean result = service.scheduleRetryIfNecessary(
        task("t1", 1L, null), partition(1L, 0), jobInst, "ERR", "msg");

    assertThat(result).isTrue();
  }

  // ── replayDeadLetter — guard conditions ──────────────────────────────────

  @Test
  @DisplayName("死信不存在时重放抛出业务异常")
  void shouldThrowWhenDeadLetterNotFound() {
    when(deadLetterTaskMapper.selectById("t1", 999L)).thenReturn(null);

    assertThatThrownBy(() -> service.replayDeadLetter("t1", 999L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.dead_letter.not_found");
  }

  @Test
  @DisplayName("死信状态不允许重放时抛出业务异常")
  void shouldThrowWhenDeadLetterNotReplayable() {
    DeadLetterTaskEntity dl = deadLetter(1L, "t1", "REPLAYING");
    when(deadLetterTaskMapper.selectById("t1", 1L)).thenReturn(dl);

    assertThatThrownBy(() -> service.replayDeadLetter("t1", 1L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.dead_letter.not_replayable");
  }

  @Test
  @DisplayName("死信重放认领未命中时抛出并发冲突异常")
  void shouldThrowWhenDeadLetterReplayConcurrencyConflict() {
    DeadLetterTaskEntity dl = deadLetter(1L, "t1", DeadLetter.NEW);
    when(deadLetterTaskMapper.selectById("t1", 1L)).thenReturn(dl);
    when(deadLetterTaskMapper.markReplaying(anyString(), anyLong(), anyString(), anyString()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.replayDeadLetter("t1", 1L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.dead_letter.replay_conflict");
  }

  @Test
  @DisplayName("死信来源类型不是分区时重放抛出业务异常")
  void shouldThrowWhenDeadLetterSourceTypeIsNotJobPartition() {
    DeadLetterTaskEntity dl = deadLetter(1L, "t1", DeadLetter.NEW);
    dl.setSourceType("JOB_TASK");
    when(deadLetterTaskMapper.selectById("t1", 1L)).thenReturn(dl);
    when(deadLetterTaskMapper.markReplaying(anyString(), anyLong(), anyString(), anyString()))
        .thenReturn(1);

    assertThatThrownBy(() -> service.replayDeadLetter("t1", 1L)).isInstanceOf(BizException.class);
    verify(deadLetterTaskMapper)
        .markReplayFailure(
            anyString(), anyLong(), anyString(), anyInt(), any(), anyString(), any());
  }

  @Test
  @DisplayName("死信对应分区行缺失时放弃重放并标记为已放弃")
  void shouldGiveUpWhenDeadLetterPartitionRowMissing() {
    DeadLetterTaskEntity dl = deadLetter(1L, "t1", DeadLetter.NEW);
    when(deadLetterTaskMapper.selectById("t1", 1L)).thenReturn(dl);
    when(deadLetterTaskMapper.markReplaying(anyString(), anyLong(), anyString(), anyString()))
        .thenReturn(1);
    when(jobPartitionMapper.selectById("t1", 100L)).thenReturn(null);

    assertThatThrownBy(() -> service.replayDeadLetter("t1", 1L))
        .isInstanceOf(DeadLetterOrphanSourceException.class);

    verify(deadLetterTaskMapper).markGiveUp("t1", 1L);
    verify(deadLetterTaskMapper, never())
        .markReplayFailure(
            anyString(), anyLong(), anyString(), anyInt(), any(), anyString(), any());
  }

  // ── dispatchDueRetries — no retries ──────────────────────────────────────

  @Test
  @DisplayName("没有到期的重试计划时不做任何处理")
  void shouldDoNothingWhenNoRetrySchedulesDue() {
    when(retryScheduleMapper.selectByQuery(any())).thenReturn(List.of());

    service.dispatchDueRetries();

    verify(retryScheduleMapper).selectByQuery(any());
    verify(retryScheduleMapper, never())
        .markRunning(anyString(), anyLong(), anyString(), anyString());
  }

  @Test
  @DisplayName("重试计划认领未命中时跳过该条, 不读取分区")
  void shouldSkipRetryWhenMarkRunningFails() {
    RetryScheduleEntity schedule = new RetryScheduleEntity();
    schedule.setId(1L);
    schedule.setTenantId("t1");
    schedule.setRelatedId(100L);
    when(retryScheduleMapper.selectByQuery(any())).thenReturn(List.of(schedule));
    when(retryScheduleMapper.markRunning(
            "t1", 1L, RetryScheduleStatus.WAITING.code(), RetryScheduleStatus.RUNNING.code()))
        .thenReturn(0);

    service.dispatchDueRetries();

    verify(partitionMapper(), never()).selectById(anyString(), anyLong());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private JobPartitionMapper partitionMapper() {
    return jobPartitionMapper;
  }

  private static JobTaskEntity task(String tenantId, Long taskId, Long jobDefinitionId) {
    JobTaskEntity t = new JobTaskEntity();
    t.setTenantId(tenantId);
    t.setId(taskId);
    t.setJobPartitionId(1L);
    return t;
  }

  private static JobPartitionEntity partition(Long partitionId, int retryCount) {
    JobPartitionEntity p = new JobPartitionEntity();
    p.setId(partitionId);
    p.setRetryCount(retryCount);
    p.setJobInstanceId(1L);
    return p;
  }

  private static JobInstanceEntity jobInstance(Long jobDefinitionId) {
    JobInstanceEntity j = new JobInstanceEntity();
    j.setId(1L);
    j.setJobDefinitionId(jobDefinitionId);
    j.setInstanceNo("INST-001");
    j.setTraceId("trace-001");
    return j;
  }

  private static JobDefinitionEntity jobDefinitionWithPolicy(
      Long id, String retryPolicy, int maxRetry) {
    return new JobDefinitionEntity(
        id,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        retryPolicy,
        maxRetry,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static DeadLetterTaskEntity deadLetter(Long id, String tenantId, String replayStatus) {
    DeadLetterTaskEntity dl = new DeadLetterTaskEntity();
    dl.setId(id);
    dl.setTenantId(tenantId);
    dl.setReplayStatus(replayStatus);
    dl.setReplayCount(0);
    dl.setSourceType("JOB_PARTITION");
    dl.setSourceId(100L);
    return dl;
  }
}
