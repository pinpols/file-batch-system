package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.kafka.SchedulingContext;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.application.TaskDispatchExecutor;
import io.github.pinpols.batch.worker.core.application.TaskDispatchExecutor.BatchItemExecution;
import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerKafkaSubscribeProperties;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import io.github.pinpols.batch.worker.core.infrastructure.DeadLetterPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.ResourceAccessException;

/**
 * Tests for AbstractTaskConsumer covering: - malformed message dropped silently - worker type
 * mismatch → message skipped without execution - selectedWorkerId mismatch → message skipped -
 * executor returns null (CLAIM race lost) → treated as skip - executor throws exception → DLQ
 * published, returns true (no requeue) - executor throws exception with no DLQ → still returns true
 * - MDC fields cleared after processing - topics() resolution logic
 */
@DisplayName("任务消费者基类: 消息过滤, 批量消费与死信转投及订阅主题解析")
class AbstractTaskConsumerTest {

  @Test
  @DisplayName("任务标识缺失的消息被静默丢弃, 返回成功且不进入执行")
  void doConsume_dropsMessageWhenTaskIdMissing() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    TaskDispatchMessage msg = buildMessage(null, "t1", "IMPORT", null);
    boolean result = consumer.doConsume(JsonUtils.toJson(msg));

    assertThat(result).isTrue();
    verify(executor, never()).execute(any(), anyString());
  }

  @Test
  @DisplayName("同一租户的多条消息合并为一次批量执行, 整批成功即允许提交偏移量")
  void doConsumeBatch_groupsAcceptedMessagesByTenantAndExecutesBatch() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    AtomicReference<String> executionTopic = new AtomicReference<>();
    when(executor.executeBatchDetailed(any(), anyString())).thenAnswer(invocation -> {
      executionTopic.set(MDC.get(StructuredLogField.TOPIC));
      return List.of();
    });
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    String j1 = JsonUtils.toJson(buildMessage(1L, "t1", "IMPORT", null));
    String j2 = JsonUtils.toJson(buildMessage(2L, "t1", "IMPORT", null));
    boolean result = consumer.doConsumeBatch(List.of(j1, j2));

    assertThat(result).isTrue(); // 整批成功 → 提交
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<TaskDispatchMessage>> cap = ArgumentCaptor.forClass(List.class);
    verify(executor).executeBatchDetailed(cap.capture(), anyString()); // 同租户一次 executeBatch
    assertThat(cap.getValue()).hasSize(2);
    verify(executor, never()).execute(any(), anyString()); // 不走单条路径
    assertThat(executionTopic.get()).isEqualTo("batch.task.dispatch.import");
    assertThat(MDC.get(StructuredLogField.TOPIC)).isNull();
  }

  @Test
  @DisplayName("批量消息为空时直接返回成功, 不触发批量执行")
  void doConsumeBatch_emptyOrNullReturnsTrueWithoutExecuting() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    assertThat(consumer.doConsumeBatch(List.of())).isTrue();
    verify(executor, never()).executeBatchDetailed(any(), anyString());
  }

  @Test
  @DisplayName("批量中解析失败的消息只把该条转入死信, 合法消息继续执行")
  void doConsumeBatch_dlqsOnlyMalformedPayloadAndKeepsGoodPayloads() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.executeBatchDetailed(any(), anyString())).thenReturn(List.of());
    DeadLetterPublisher dlq = mock(DeadLetterPublisher.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, dlq);

    String good = JsonUtils.toJson(buildMessage(1L, "t1", "IMPORT", null));
    String bad = "{not-json";

    boolean result = consumer.doConsumeBatch(List.of(good, bad));

    assertThat(result).isTrue();
    verify(dlq).publish(org.mockito.ArgumentMatchers.eq(bad), any(), any(), any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<TaskDispatchMessage>> cap = ArgumentCaptor.forClass(List.class);
    verify(executor).executeBatchDetailed(cap.capture(), anyString());
    assertThat(cap.getValue()).extracting(TaskDispatchMessage::taskId).containsExactly(1L);
  }

  @Test
  @DisplayName("批量结果中仅永久失败的条目转入死信")
  void doConsumeBatch_dlqsOnlyFailedItemWhenBatchItemFailsNonTransient() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    DeadLetterPublisher dlq = mock(DeadLetterPublisher.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, dlq);
    TaskDispatchMessage m1 = buildMessage(1L, "t1", "IMPORT", null);
    TaskDispatchMessage m2 = buildMessage(2L, "t1", "IMPORT", null);
    String p1 = JsonUtils.toJson(m1);
    String p2 = JsonUtils.toJson(m2);
    when(executor.executeBatchDetailed(any(), anyString()))
        .thenReturn(List.of(
            BatchItemExecution.completed(0, m1, new WorkerExecutionResult("1", true, "ok")),
            BatchItemExecution.failed(1, m2, new IllegalArgumentException("bad item"))));

    boolean result = consumer.doConsumeBatch(List.of(p1, p2));

    assertThat(result).isTrue();
    verify(dlq).publish(org.mockito.ArgumentMatchers.eq(p2), any(), any(), any());
  }

  @Test
  @DisplayName("两条消息内容相等时按载荷位置定位失败项, 只转投对应位置的消息")
  void doConsumeBatch_keepsPayloadPositionWhenMessagesCompareEqual() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    DeadLetterPublisher dlq = mock(DeadLetterPublisher.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, dlq);
    TaskDispatchMessage first = buildMessage(1L, "t1", "IMPORT", null);
    TaskDispatchMessage second = buildMessage(1L, "t1", "IMPORT", null);
    String firstPayload = JsonUtils.toJson(first);
    String secondPayload = " " + firstPayload;
    when(executor.executeBatchDetailed(any(), anyString()))
        .thenReturn(List.of(
            BatchItemExecution.failed(0, first, new IllegalArgumentException("first failed")),
            BatchItemExecution.completed(1, second, new WorkerExecutionResult("1", true, "ok"))));

    boolean result = consumer.doConsumeBatch(List.of(firstPayload, secondPayload));

    assertThat(result).isTrue();
    verify(dlq).publish(org.mockito.ArgumentMatchers.eq(firstPayload), any(), any(), any());
    verify(dlq, never())
        .publish(org.mockito.ArgumentMatchers.eq(secondPayload), any(), any(), any());
  }

  @Test
  @DisplayName("Worker 类型缺失的消息被丢弃, 不进入执行")
  void doConsume_dropsMessageWhenWorkerTypeMissing() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    TaskDispatchMessage msg = buildMessage(1L, "t1", null, null);
    boolean result = consumer.doConsume(JsonUtils.toJson(msg));

    assertThat(result).isTrue();
    verify(executor, never()).execute(any(), anyString());
  }

  @Test
  @DisplayName("Worker 类型与当前消费者不匹配时跳过该消息")
  void doConsume_skipsWhenWorkerTypeMismatch() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    AbstractTaskConsumer consumer = buildConsumer("EXPORT", executor, null);

    TaskDispatchMessage msg = buildMessage(1L, "t1", "IMPORT", null);
    boolean result = consumer.doConsume(JsonUtils.toJson(msg));

    assertThat(result).isTrue();
    verify(executor, never()).execute(any(), anyString());
  }

  @Test
  @DisplayName("消息指定的目标 Worker 与当前消费者不一致时跳过")
  void doConsume_skipsWhenSelectedWorkerIdDoesNotMatch() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null, "worker-A");

    // Message targets worker-B, but this consumer is worker-A
    TaskDispatchMessage msg = new TaskDispatchMessage(
        "v2", "t1", 1L, null, 1L, null, null, "IMPORT", "worker-B", null, "tr", "k", null, null);
    boolean result = consumer.doConsume(JsonUtils.toJson(msg));

    assertThat(result).isTrue();
    verify(executor, never()).execute(any(), anyString());
  }

  @Test
  @DisplayName("消息指定的目标 Worker 与当前消费者一致时执行该消息")
  void doConsume_executesWhenSelectedWorkerIdMatches() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenReturn(new WorkerExecutionResult("1", true, "ok"));
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null, "worker-A");

    TaskDispatchMessage msg = new TaskDispatchMessage(
        "v2", "t1", 1L, null, 1L, null, null, "IMPORT", "worker-A", null, "tr", "k", null, null);
    consumer.doConsume(JsonUtils.toJson(msg));

    verify(executor).execute(any(), anyString());
  }

  @Test
  @DisplayName("执行结果为空表示领取竞争失败, 按跳过处理并返回成功")
  void doConsume_treatsNullExecutorResultAsSkip() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenReturn(null); // CLAIM race lost
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    boolean result = consumer.doConsume(buildImportMessage());

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("执行过程抛异常时把消息转入死信, 返回成功以避免重复投递")
  void doConsume_publishesToDlqOnException() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenThrow(new RuntimeException("unexpected failure"));
    DeadLetterPublisher dlq = mock(DeadLetterPublisher.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, dlq);

    boolean result = consumer.doConsume(buildImportMessage());

    assertThat(result).isTrue();
    verify(dlq).publish(any(), any(), any(), any());
  }

  @Test
  @DisplayName("执行抛异常且没有死信通道时仍返回成功")
  void doConsume_returnsTrueEvenWhenExceptionAndNoDlq() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenThrow(new RuntimeException("boom"));
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    boolean result = consumer.doConsume(buildImportMessage());

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("编排端暂时不可用时单条消费要求延迟重投, 不确认偏移量")
  void consume_nacksTransientOrchestratorFailureForRedelivery() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any()))
        .thenThrow(new ResourceAccessException("orchestrator temporarily unavailable"));
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);
    Acknowledgment acknowledgment = mock(Acknowledgment.class);

    consumer.consume(buildImportMessage(), acknowledgment);

    verify(acknowledgment).nack(Duration.ofSeconds(1));
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("编排端暂时不可用时批量消费要求延迟重投, 不确认偏移量")
  void consumeBatch_nacksTransientOrchestratorFailureForRedelivery() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.executeBatchDetailed(any(), anyString()))
        .thenThrow(new ResourceAccessException("orchestrator temporarily unavailable"));
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);
    Acknowledgment acknowledgment = mock(Acknowledgment.class);

    consumer.consumeBatch(List.of(buildImportMessage()), acknowledgment);

    verify(acknowledgment).nack(Duration.ofSeconds(1));
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("执行成功后清理日志上下文中的租户, 追踪与任务字段")
  void doConsume_clearsMdcAfterSuccessfulExecution() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenAnswer(inv -> {
      MDC.put(StructuredLogField.TENANT_ID, "should-be-cleared");
      return new WorkerExecutionResult("1", true, "ok");
    });
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    consumer.doConsume(buildImportMessage());

    assertThat(MDC.get(StructuredLogField.TENANT_ID)).isNull();
    assertThat(MDC.get(StructuredLogField.TRACE_ID)).isNull();
    assertThat(MDC.get(StructuredLogField.TASK_ID)).isNull();
  }

  @Test
  @DisplayName("执行期间注入批量主链最小日志字段, 完成后清理线程上下文")
  void doConsume_exposesMinimumBatchTraceFieldsDuringExecution() {
    AtomicReference<Map<String, String>> executionMdc = new AtomicReference<>();
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenAnswer(invocation -> {
      executionMdc.set(MDC.getCopyOfContextMap());
      return new WorkerExecutionResult("1", true, "ok");
    });
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);
    SchedulingContext schedulingContext = new SchedulingContext(
        LocalDate.of(2026, Month.OCTOBER, 7), null, null, false, 3, "API", null, 901L);
    TaskDispatchMessage message = new TaskDispatchMessage(
        "v2",
        "t1",
        1L,
        11L,
        101L,
        null,
        "JOB-1",
        "IMPORT",
        null,
        null,
        "tr",
        "k",
        null,
        schedulingContext);

    consumer.doConsume(JsonUtils.toJson(message));

    assertThat(executionMdc.get())
        .containsEntry(StructuredLogField.TRACE_ID, "tr")
        .containsEntry(StructuredLogField.TENANT_ID, "t1")
        .containsEntry(StructuredLogField.JOB_INSTANCE_ID, "1")
        .containsEntry(StructuredLogField.WORKFLOW_RUN_ID, "901")
        .containsEntry(StructuredLogField.PARTITION_ID, "11")
        .containsEntry(StructuredLogField.TASK_ID, "101")
        .containsEntry(StructuredLogField.BATCH_DAY, "2026-10-07")
        .containsEntry(StructuredLogField.ATTEMPT, "3")
        .containsEntry(StructuredLogField.TOPIC, "batch.task.dispatch.import");
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  @Test
  @DisplayName("执行抛异常后同样清理日志上下文中的租户与追踪字段")
  void doConsume_clearsMdcAfterException() {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    when(executor.execute(any(), any())).thenThrow(new RuntimeException("fail"));
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, null);

    consumer.doConsume(buildImportMessage());

    assertThat(MDC.get(StructuredLogField.TENANT_ID)).isNull();
    assertThat(MDC.get(StructuredLogField.TRACE_ID)).isNull();
  }

  @Test
  @DisplayName("没有 Worker 编码时只返回基础主题")
  void topics_returnsBaseTopicOnlyWhenNoWorkerCode() {
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", mock(TaskDispatchExecutor.class), null);
    String[] topics = consumer.topics();
    assertThat(topics).hasSize(1);
    assertThat(topics[0]).contains("import");
  }

  @Test
  @DisplayName("处理类型解析为对应的派发主题")
  void topics_resolvesProcessWorkerTypeToProcessDispatchTopic() {
    AbstractTaskConsumer consumer =
        buildConsumer("PROCESS", mock(TaskDispatchExecutor.class), null);
    String[] topics = consumer.topics();
    assertThat(topics).containsExactly("batch.task.dispatch.process");
  }

  @Test
  @DisplayName("原子类型解析为对应的派发主题")
  void topics_resolvesAtomicWorkerTypeToAtomicDispatchTopic() {
    AbstractTaskConsumer consumer = buildConsumer("ATOMIC", mock(TaskDispatchExecutor.class), null);
    assertThat(consumer.topics()).containsExactly("batch.task.dispatch.atomic");
  }

  @Test
  @DisplayName("存在 Worker 编码时同时返回基础主题与节点直投主题")
  void topics_returnsBothBaseAndDirectTopicWhenWorkerCodePresent() {
    AbstractTaskConsumer consumer =
        buildConsumer("IMPORT", mock(TaskDispatchExecutor.class), null, "w1");
    String[] topics = consumer.topics();
    assertThat(topics).hasSize(2);
  }

  // ── P2-5 worker pattern: 匹配 SINGLE / TENANT / PRIORITY 三种 producer 输出 ───────────────

  @Test
  @DisplayName("订阅主题匹配串同时命中基础主题, 自身节点直投与单段后缀, 不命中他人节点与其它类型")
  void topicPattern_matchesBaseAndNodeDirectAndSingleSegmentSuffix() {
    AbstractTaskConsumer consumer =
        buildConsumer("IMPORT", mock(TaskDispatchExecutor.class), null, "import-node-1");
    Pattern p = Pattern.compile(consumer.topicPattern());

    // ✅ base
    assertThat(p.matcher("batch.task.dispatch.import").matches()).isTrue();
    // ✅ node-direct（自己的 workerCode）
    assertThat(p.matcher("batch.task.dispatch.import.node.import-node-1").matches())
        .isTrue();
    // ✅ TENANT 后缀（一段后缀）
    assertThat(p.matcher("batch.task.dispatch.import.default-tenant").matches()).isTrue();
    // ✅ PRIORITY 后缀
    assertThat(p.matcher("batch.task.dispatch.import.high").matches()).isTrue();

    // ❌ 别人的 node-direct（双段 .node.<otherCode>）
    assertThat(p.matcher("batch.task.dispatch.import.node.import-node-2").matches())
        .isFalse();
    // ❌ 不同 workerType 的 base
    assertThat(p.matcher("batch.task.dispatch.export").matches()).isFalse();
    // ❌ TENANT 后缀里有 dot（双段非 node 形态）
    assertThat(p.matcher("batch.task.dispatch.import.tenant.subseg").matches()).isFalse();
  }

  @Test
  @DisplayName("没有 Worker 编码时仍允许单段租户后缀, 但不允许节点直投后缀")
  void topicPattern_withoutWorkerCodeStillAllowsTenantSuffix() {
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", mock(TaskDispatchExecutor.class), null);
    Pattern p = Pattern.compile(consumer.topicPattern());

    assertThat(p.matcher("batch.task.dispatch.import").matches()).isTrue();
    assertThat(p.matcher("batch.task.dispatch.import.t1").matches()).isTrue();
    // 没 workerCode 时不允许任何 node-direct（双段后缀）
    assertThat(p.matcher("batch.task.dispatch.import.node.x").matches()).isFalse();
  }

  // ── 方案 A：FIXED 模式只匹配 base + node-direct ────────────────────────

  @Test
  @DisplayName("固定订阅模式只匹配基础主题与自身节点直投, 不匹配任何租户或优先级后缀")
  void topicPattern_fixedModeOnlyMatchesBaseAndNodeDirect() throws Exception {
    AbstractTaskConsumer consumer =
        buildConsumer("IMPORT", mock(TaskDispatchExecutor.class), null, "import-node-1");
    WorkerKafkaSubscribeProperties props = new WorkerKafkaSubscribeProperties();
    props.setSubscribeMode(WorkerKafkaSubscribeProperties.Mode.FIXED);
    setSubscribeProperties(consumer, props);

    Pattern p = Pattern.compile(consumer.topicPattern());

    assertThat(p.matcher("batch.task.dispatch.import").matches()).isTrue();
    assertThat(p.matcher("batch.task.dispatch.import.node.import-node-1").matches())
        .isTrue();
    // FIXED 模式不订阅任何 tenant/priority 后缀
    assertThat(p.matcher("batch.task.dispatch.import.t1").matches()).isFalse();
    assertThat(p.matcher("batch.task.dispatch.import.high").matches()).isFalse();
  }

  // ── 方案 A：TENANT_SCOPED 仅匹配 allowlist 中的租户 ──────────────────

  @Test
  @DisplayName("租户限定模式只匹配白名单中的租户后缀, 白名单外与优先级后缀均不匹配")
  void topicPattern_tenantScopedModeMatchesAllowlistOnly() throws Exception {
    AbstractTaskConsumer consumer =
        buildConsumer("IMPORT", mock(TaskDispatchExecutor.class), null, "import-node-1");
    WorkerKafkaSubscribeProperties props = new WorkerKafkaSubscribeProperties();
    props.setSubscribeMode(WorkerKafkaSubscribeProperties.Mode.TENANT_SCOPED);
    props.setTenantAllowlist(List.of("t1", "t2"));
    setSubscribeProperties(consumer, props);

    Pattern p = Pattern.compile(consumer.topicPattern());

    assertThat(p.matcher("batch.task.dispatch.import").matches()).isTrue();
    assertThat(p.matcher("batch.task.dispatch.import.node.import-node-1").matches())
        .isTrue();
    // ✅ allowlist 中的租户
    assertThat(p.matcher("batch.task.dispatch.import.t1").matches()).isTrue();
    assertThat(p.matcher("batch.task.dispatch.import.t2").matches()).isTrue();
    // ❌ 不在 allowlist 中
    assertThat(p.matcher("batch.task.dispatch.import.t3").matches()).isFalse();
    assertThat(p.matcher("batch.task.dispatch.import.high").matches()).isFalse();
  }

  @Test
  @DisplayName("租户限定模式在白名单为空时回退为固定模式, 不再匹配租户后缀")
  void topicPattern_tenantScopedWithEmptyAllowlistFallsBackToFixed() throws Exception {
    AbstractTaskConsumer consumer =
        buildConsumer("IMPORT", mock(TaskDispatchExecutor.class), null, "import-node-1");
    WorkerKafkaSubscribeProperties props = new WorkerKafkaSubscribeProperties();
    props.setSubscribeMode(WorkerKafkaSubscribeProperties.Mode.TENANT_SCOPED);
    props.setTenantAllowlist(List.of());
    setSubscribeProperties(consumer, props);

    Pattern p = Pattern.compile(consumer.topicPattern());

    assertThat(p.matcher("batch.task.dispatch.import").matches()).isTrue();
    assertThat(p.matcher("batch.task.dispatch.import.node.import-node-1").matches())
        .isTrue();
    assertThat(p.matcher("batch.task.dispatch.import.t1").matches()).isFalse();
  }

  @Test
  @DisplayName("节点直投模式只匹配自身稳定实例池主题, 不匹配基础主题与其它实例池")
  void topicPattern_directOnlyMatchesOnlyItsStablePoolTopic() throws Exception {
    TaskDispatchExecutor executor = mock(TaskDispatchExecutor.class);
    DeadLetterPublisher dlq = mock(DeadLetterPublisher.class);
    AbstractTaskConsumer consumer = buildConsumer("IMPORT", executor, dlq, "import-heavy");
    WorkerKafkaSubscribeProperties props = new WorkerKafkaSubscribeProperties();
    props.setSubscribeMode(WorkerKafkaSubscribeProperties.Mode.DIRECT_ONLY);
    setSubscribeProperties(consumer, props);

    Pattern pattern = Pattern.compile(consumer.topicPattern());
    assertThat(pattern.matcher("batch.task.dispatch.import.node.import-heavy").matches())
        .isTrue();
    assertThat(pattern.matcher("batch.task.dispatch.import").matches()).isFalse();
    assertThat(pattern.matcher("batch.task.dispatch.import.t1").matches()).isFalse();
    assertThat(pattern.matcher("batch.task.dispatch.import.node.import-light").matches())
        .isFalse();
  }

  @Test
  @DisplayName("节点直投模式使用与生产端一致的规范化主题名, 原始带空格与斜杠的名称不匹配")
  void topicPattern_directOnlyUsesTheSameSanitizedTopicAsProducer() throws Exception {
    AbstractTaskConsumer consumer =
        buildConsumer("IMPORT", mock(TaskDispatchExecutor.class), null, "import/heavy pool");
    WorkerKafkaSubscribeProperties props = new WorkerKafkaSubscribeProperties();
    props.setSubscribeMode(WorkerKafkaSubscribeProperties.Mode.DIRECT_ONLY);
    setSubscribeProperties(consumer, props);

    Pattern pattern = Pattern.compile(consumer.topicPattern());

    assertThat(
            pattern.matcher("batch.task.dispatch.import.node.import_heavy_pool").matches())
        .isTrue();
    assertThat(
            pattern.matcher("batch.task.dispatch.import.node.import/heavy pool").matches())
        .isFalse();
  }

  @Test
  @DisplayName("节点直投模式缺少稳定实例池编码时抛出状态异常并提示原因")
  void topicPattern_directOnlyRequiresStablePoolCode() throws Exception {
    AbstractTaskConsumer consumer =
        buildConsumer("IMPORT", mock(TaskDispatchExecutor.class), null, null);
    WorkerKafkaSubscribeProperties props = new WorkerKafkaSubscribeProperties();
    props.setSubscribeMode(WorkerKafkaSubscribeProperties.Mode.DIRECT_ONLY);
    setSubscribeProperties(consumer, props);

    org.assertj.core.api.Assertions.assertThatThrownBy(consumer::topicPattern)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DIRECT_ONLY")
        .hasMessageContaining("worker code");
  }

  private static void setSubscribeProperties(
      AbstractTaskConsumer consumer, WorkerKafkaSubscribeProperties props) throws Exception {
    Field f = AbstractTaskConsumer.class.getDeclaredField("subscribeProperties");
    f.setAccessible(true);
    f.set(consumer, props);
  }

  // ------------------------------------------------------------------ helpers

  private AbstractTaskConsumer buildConsumer(
      String workerType, TaskDispatchExecutor executor, DeadLetterPublisher dlq) {
    return buildConsumer(workerType, executor, dlq, null);
  }

  private AbstractTaskConsumer buildConsumer(
      String workerType,
      TaskDispatchExecutor executorArg,
      DeadLetterPublisher dlqArg,
      String workerCode) {
    KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
    WorkerLifecycleManager lifecycleManager = mock(WorkerLifecycleManager.class);
    HeartbeatService heartbeatService = mock(HeartbeatService.class);
    WorkerRegistration registration = mock(WorkerRegistration.class);
    when(registration.getWorkerId()).thenReturn(workerCode != null ? workerCode : "w-default");
    when(lifecycleManager.start(any())).thenReturn(registration);

    WorkerConfiguration cfg = workerConfiguration(workerType, workerCode);

    return new AbstractTaskConsumer(registry, meterRegistryProvider, concurrencyProperties(8)) {
      @Override
      protected AbstractWorkerLoop workerLoop() {
        AbstractWorkerLoop loop =
            new AbstractWorkerLoop(lifecycleManager, heartbeatService, dateTimeSupport(), 8) {
              @Override
              protected WorkerConfiguration workerConfiguration() {
                return cfg;
              }

              @Override
              protected String workerGroup() {
                return "test";
              }
            };
        loop.setEnvironment(new MockEnvironment().withProperty("local.server.port", "19083"));
        return loop;
      }

      @Override
      protected WorkerConfiguration workerConfiguration() {
        return cfg;
      }

      @Override
      protected TaskDispatchExecutor taskDispatchExecutor() {
        return executorArg;
      }

      @Override
      public String listenerId() {
        return "test-listener";
      }

      @Override
      protected DeadLetterPublisher deadLetterPublisher() {
        return dlqArg;
      }
    };
  }

  private static BatchDateTimeSupport dateTimeSupport() {
    return new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
  }

  private WorkerConfiguration workerConfiguration(String workerType, String workerCode) {
    return new WorkerConfiguration() {
      @Override
      public String workerCode() {
        return workerCode;
      }

      @Override
      public String workerType() {
        return workerType;
      }

      @Override
      public String tenantId() {
        return "t1";
      }

      @Override
      public Long heartbeatIntervalMillis() {
        return 1000L;
      }

      @Override
      public String topic() {
        return null; // trigger topic fallback resolution
      }

      @Override
      public String consumerGroupId() {
        return "g";
      }
    };
  }

  private String buildImportMessage() {
    return JsonUtils.toJson(buildMessage(1L, "t1", "IMPORT", null));
  }

  private TaskDispatchMessage buildMessage(
      Long taskId, String tenantId, String workerType, String selectedWorkerId) {
    // P1-2.2 v2 字段顺序:schemaVersion, tenantId, jobInstanceId, jobPartitionId, taskId,
    //                   instanceNo, jobCode, workerType, selectedWorkerId, priorityBand,
    //                   traceId, idempotencyKey, dispatchAt
    return new TaskDispatchMessage(
        "v2",
        tenantId,
        1L,
        null,
        taskId,
        null,
        null,
        workerType,
        selectedWorkerId,
        null,
        "tr",
        "k",
        null,
        null);
  }

  private static WorkerConcurrencyProperties concurrencyProperties(int maxConcurrentTasks) {
    WorkerConcurrencyProperties properties = new WorkerConcurrencyProperties();
    properties.setMaxConcurrentTasks(maxConcurrentTasks);
    return properties;
  }
}
