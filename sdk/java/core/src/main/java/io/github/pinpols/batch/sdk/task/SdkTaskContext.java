package io.github.pinpols.batch.sdk.task;

import io.github.pinpols.batch.sdk.checkpoint.SdkCheckpoint;
import io.github.pinpols.batch.sdk.internal.EmptyChecks;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Builder;

/**
 * 任务执行上下文 — 由 SDK 框架从派单消息构造,传给 {@link SdkTaskHandler#execute(SdkTaskContext)}。
 *
 * <p>跟主项目 {@code io.github.pinpols.batch.common.spi.task.TaskContext} 对齐,**结构等价**(平台→SDK 协议层契约,
 * 同样的字段集),但 SDK 这边不引 batch-common 避免传递依赖膨胀。
 *
 * <p>Phase 2 §2.2:新增 {@link #schedulingContext},并暴露 7 个便捷 getter ({@link #bizDate()} / {@link
 * #prevBizDate()} / {@link #nextBizDate()} / {@link #isHoliday()} / {@link #attemptNo()} / {@link
 * #triggerCode()} / {@link #workflowRunId()}),让 handler 直接拿调度事实做增量逻辑,无需回调平台。
 *
 * @param tenantId 租户 ID(必传)
 * @param jobCode 作业编码
 * @param taskInstanceId 本次执行的 task instance ID
 * @param taskId orchestrator 端 task 主键(用于 CLAIM / REPORT)
 * @param workerId 当前 worker 标识(SDK 在 register 时分配)
 * @param parameters 当前任务的权威业务载荷(由 CLAIM 从持久化 job_task.task_payload 返回)
 * @param runtimeAttributes 平台注入的运行时属性(traceId / bizDate / pipelineInstanceId 等)
 * @param schedulingContext Phase 2 调度上下文;老平台未下发时为 null,便捷 getter 一律 null-safe
 * @param cancellation Phase 4 取消信号;由 dispatcher 注入,null 时构造器补一个永不取消的空信号(getter null-safe)
 * @param progress Phase 4 进度上报槽;由 dispatcher 注入,null 时构造器补一个空槽(handler 调 reportProgress 即写入)
 * @param commitCoordinator ADR-037 三合一可靠提交协调器;由续跑模板 / dispatcher 注入,null 时构造器补一个内存断点的空协调器(getter
 *     null-safe)
 * @param dependencyResultSnapshot Orchestrator 依赖门禁证据；使用结果前，消费者必须显式固定或校验读取版本。
 */
@Builder
public record SdkTaskContext(
    String tenantId,
    String jobCode,
    String taskInstanceId,
    Long taskId,
    String workerId,
    Map<String, Object> parameters,
    Map<String, Object> runtimeAttributes,
    SdkSchedulingContext schedulingContext,
    CancellationSignal cancellation,
    ProgressReporter progress,
    SdkCommitCoordinator commitCoordinator,
    List<SdkDependencyResultReference> dependencyResultSnapshot) {

  public SdkTaskContext {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(jobCode, "jobCode");
    Objects.requireNonNull(taskId, "taskId");
    parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    runtimeAttributes = runtimeAttributes == null ? Map.of() : Map.copyOf(runtimeAttributes);
    dependencyResultSnapshot = EmptyChecks.isNull(dependencyResultSnapshot)
        ? List.of()
        : List.copyOf(dependencyResultSnapshot);
    cancellation = cancellation == null ? new CancellationSignal() : cancellation;
    progress = progress == null ? new ProgressReporter() : progress;
    // ADR-037: 默认空协调器(内存断点 + 默认限流),让未走续跑模板的 handler 调 commit/checkpoint 也 null-safe。
    commitCoordinator = commitCoordinator == null
        ? new SdkCommitCoordinator(String.valueOf(taskId), null, progress, cancellation, true, 1)
        : commitCoordinator;
  }

  /**
   * 返回冻结在 {@code parameters.crossDay.<alias>} 中的内联结果版本。
   *
   * <p>处理器必须读取这些快照，不得在执行期间查询当前生效版本或最新版本。
   * 别名缺失表示该依赖为可选项；若别名存在但格式错误或不受支持，则拒绝该载荷。
   * 遇到不支持的格式时按失败关闭处理，避免消费者静默替换为其他版本。
   */
  public List<SdkCrossDayResult> crossDayResults(String alias) {
    if (EmptyChecks.isBlank(alias)) {
      throw new IllegalArgumentException("cross-day alias is required");
    }
    Object rawCrossDay = parameters.get("crossDay");
    if (EmptyChecks.isNull(rawCrossDay)) {
      return List.of();
    }
    if (!(rawCrossDay instanceof Map<?, ?> crossDay)) {
      throw new IllegalArgumentException("crossDay payload must be an object");
    }
    if (!crossDay.containsKey(alias)) {
      return List.of();
    }
    Object rawEntry = crossDay.get(alias);
    if (!(rawEntry instanceof Map<?, ?> entry)) {
      throw new IllegalArgumentException("cross-day result entry must be an object: " + alias);
    }
    Object rawOutputs = entry.get("outputs");
    if (entry.containsKey("outputs")) {
      if (!(rawOutputs instanceof List<?> outputs)) {
        throw new IllegalArgumentException("cross-day outputs must be an array: " + alias);
      }
      List<SdkCrossDayResult> results = new ArrayList<>(outputs.size());
      for (Object rawOutput : outputs) {
        if (!(rawOutput instanceof Map<?, ?> output)) {
          throw new IllegalArgumentException("cross-day output entry must be an object: " + alias);
        }
        SdkCrossDayResult parsedResult = parseCrossDayResult(alias, output);
        results.add(parsedResult);
      }
      return List.copyOf(results);
    }
    SdkCrossDayResult result = parseCrossDayResult(alias, entry);
    return List.of(result);
  }

  private static SdkCrossDayResult parseCrossDayResult(String alias, Map<?, ?> entry) {
    if (!"INLINE_JSON".equals(entry.get("payloadStorage"))) {
      throw new IllegalArgumentException("unsupported cross-day payload storage: " + alias);
    }
    Object rawOutput = entry.get("output");
    if (!(rawOutput instanceof Map<?, ?> output)) {
      throw new IllegalArgumentException("cross-day inline output must be an object: " + alias);
    }
    long resultVersionId = positiveLong(entry.get("resultVersionId"), "resultVersionId", alias);
    String businessKey = requiredString(entry.get("businessKey"), "businessKey", alias);
    int versionNo = positiveInteger(entry.get("versionNo"), alias);
    Long jobInstanceId = optionalLong(entry.get("jobInstanceId"), "jobInstanceId", alias);
    Map<String, Object> resultOutput = stringKeyedMap(output, alias);
    return new SdkCrossDayResult(
        resultVersionId, businessKey, versionNo, jobInstanceId, resultOutput);
  }

  private static long positiveLong(Object value, String field, String alias) {
    Long parsed = optionalLong(value, field, alias);
    if (EmptyChecks.isNull(parsed) || parsed <= 0) {
      throw new IllegalArgumentException("cross-day " + field + " must be positive: " + alias);
    }
    return parsed;
  }

  private static Long optionalLong(Object value, String field, String alias) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("cross-day " + field + " must be numeric: " + alias);
    }
    try {
      String numericText = number.toString();
      BigDecimal decimalValue = new BigDecimal(numericText);
      return decimalValue.longValueExact();
    } catch (NumberFormatException | ArithmeticException ex) {
      throw new IllegalArgumentException("cross-day " + field + " must be an integer: " + alias);
    }
  }

  private static int positiveInteger(Object value, String alias) {
    Long parsed = optionalLong(value, "versionNo", alias);
    boolean versionNumberIsInvalid =
        EmptyChecks.isNull(parsed) || parsed <= 0 || parsed > Integer.MAX_VALUE;
    if (versionNumberIsInvalid) {
      throw new IllegalArgumentException("cross-day versionNo must be positive: " + alias);
    }
    return parsed.intValue();
  }

  private static String requiredString(Object value, String field, String alias) {
    if (!(value instanceof String text) || EmptyChecks.isBlank(text)) {
      throw new IllegalArgumentException("cross-day " + field + " is required: " + alias);
    }
    return text;
  }

  private static Map<String, Object> stringKeyedMap(Map<?, ?> source, String alias) {
    Map<String, Object> parsed = new LinkedHashMap<>();
    source.forEach((key, value) -> {
      if (!(key instanceof String text)) {
        throw new IllegalArgumentException("cross-day output keys must be strings: " + alias);
      }
      parsed.put(text, value);
    });
    return parsed;
  }

  /** 11 参兼容构造器 —— ADR-037 前完整调用方式继续可用。 */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public SdkTaskContext(
      String tenantId,
      String jobCode,
      String taskInstanceId,
      Long taskId,
      String workerId,
      Map<String, Object> parameters,
      Map<String, Object> runtimeAttributes,
      SdkSchedulingContext schedulingContext,
      CancellationSignal cancellation,
      ProgressReporter progress,
      SdkCommitCoordinator commitCoordinator) {
    this(
        tenantId,
        jobCode,
        taskInstanceId,
        taskId,
        workerId,
        parameters,
        runtimeAttributes,
        schedulingContext,
        cancellation,
        progress,
        commitCoordinator,
        List.of());
  }

  /** 10 参兼容构造器 —— ADR-037 前的构造方式继续可用,commit 走默认协调器。 */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public SdkTaskContext(
      String tenantId,
      String jobCode,
      String taskInstanceId,
      Long taskId,
      String workerId,
      Map<String, Object> parameters,
      Map<String, Object> runtimeAttributes,
      SdkSchedulingContext schedulingContext,
      CancellationSignal cancellation,
      ProgressReporter progress) {
    this(
        tenantId,
        jobCode,
        taskInstanceId,
        taskId,
        workerId,
        parameters,
        runtimeAttributes,
        schedulingContext,
        cancellation,
        progress,
        null);
  }

  /** 9 参兼容构造器 —— SDK-P4-1 的构造方式继续可用,progress 走空槽。 */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public SdkTaskContext(
      String tenantId,
      String jobCode,
      String taskInstanceId,
      Long taskId,
      String workerId,
      Map<String, Object> parameters,
      Map<String, Object> runtimeAttributes,
      SdkSchedulingContext schedulingContext,
      CancellationSignal cancellation) {
    this(
        tenantId,
        jobCode,
        taskInstanceId,
        taskId,
        workerId,
        parameters,
        runtimeAttributes,
        schedulingContext,
        cancellation,
        null,
        null);
  }

  /** 8 参兼容构造器 —— Phase 4 前的构造方式继续可用,cancellation / progress 走空。 */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public SdkTaskContext(
      String tenantId,
      String jobCode,
      String taskInstanceId,
      Long taskId,
      String workerId,
      Map<String, Object> parameters,
      Map<String, Object> runtimeAttributes,
      SdkSchedulingContext schedulingContext) {
    this(
        tenantId,
        jobCode,
        taskInstanceId,
        taskId,
        workerId,
        parameters,
        runtimeAttributes,
        schedulingContext,
        null,
        null);
  }

  /** 7 参兼容构造器 —— Phase 2 前的构造方式继续可用,schedulingContext 走 null。 */
  public SdkTaskContext(
      String tenantId,
      String jobCode,
      String taskInstanceId,
      Long taskId,
      String workerId,
      Map<String, Object> parameters,
      Map<String, Object> runtimeAttributes) {
    this(tenantId, jobCode, taskInstanceId, taskId, workerId, parameters, runtimeAttributes, null);
  }

  /**
   * 平台是否已请求取消本 task(运维 cancel / ORCH-P4-2 超时 / lease 被回收)。
   *
   * <p>长任务 handler 应在循环里周期 check,例如:
   *
   * <pre>{@code
   * for (var batch : batches) {
   *   if (ctx.isCancelled()) {
   *     return SdkTaskResult.fail("cancelled by platform");
   *   }
   *   process(batch);
   * }
   * }</pre>
   */
  public boolean isCancelled() {
    return cancellation.isCancelled();
  }

  /** 本次任务是否为无业务副作用演练；运行属性或兼容参数任一为 true 即视为演练。 */
  public boolean isDryRun() {
    boolean runtimeDryRun = booleanAttribute(runtimeAttributes.get("dryRun"));
    if (runtimeDryRun) {
      return true;
    }
    return booleanAttribute(parameters.get("dryRun"));
  }

  private static boolean booleanAttribute(Object value) {
    return Boolean.TRUE.equals(value) || value instanceof String text && Boolean.parseBoolean(text);
  }

  /**
   * 上报进度 / checkpoint 快照 —— 下一次 lease 续租 tick 会把最新快照作为 {@code details} 捎给平台,落 job_task 供 console
   * 任务详情读取。「最新值覆盖」语义:频繁调用只保留最近一次,不积压。
   *
   * <p><b>禁传敏感凭据</b>(DB 密码 / OAuth secret 走环境变量,roadmap §5.5)。details 不得含 null 键 / 值。
   *
   * <p>长任务 handler 典型用法:
   *
   * <pre>{@code
   * int done = 0;
   * for (var batch : batches) {
   *   process(batch);
   *   ctx.reportProgress(Map.of("processed", done += batch.size(), "total", total));
   * }
   * }</pre>
   */
  public void reportProgress(Map<String, Object> details) {
    progress.report(details);
  }

  /**
   * ADR-037 决策一 — 断点续跑存储入口。续跑模板在 execute 开头 {@code checkpoint().load(taskId)}
   * 读回上次断点;租户也可直接用它实现自定义续跑逻辑。
   *
   * <p>未注入自定义 {@link SdkCheckpoint} 时返回内存默认实现(供示例 / 测试,无持久化)。
   */
  public SdkCheckpoint checkpoint() {
    return commitCoordinator.checkpoint();
  }

  /**
   * ADR-037 决策二 + 决策三 — <b>三合一可靠提交</b>:一次调用原子完成「保存断点(同事务)+ 限流上报进度 + 取消安全点检查」。
   *
   * <p>每个业务批次写完后调一次。<b>强约束</b>:断点保存必须与业务数据在同一事务边界内(见 {@link SdkCheckpoint});JDBC 默认实现走同一个 {@link
   * java.sql.Connection} 的 {@code commit()}。提交成功后若平台已请求取消,在<b>已提交的安全点</b>抛 {@link
   * SdkTaskStoppedException}(业务<b>不得吞</b>),模板顶层捕获落 cancelled 终态。
   *
   * @param breakPosition 本批已处理到的断点坐标(业务主键 / 排序键 / 行号)
   * @throws SdkTaskStoppedException 提交后命中取消标志时
   */
  public void commit(Map<String, Object> breakPosition) {
    commitCoordinator.commit(breakPosition);
  }

  /** 实例业务日;无调度上下文时返回 null。 */
  public LocalDate bizDate() {
    return schedulingContext == null ? null : schedulingContext.bizDate();
  }

  /** 前一个业务日(近似:跳过周末);无调度上下文时返回 null。 */
  public LocalDate prevBizDate() {
    return schedulingContext == null ? null : schedulingContext.prevBizDate();
  }

  /** 下一个业务日(近似:跳过周末);无调度上下文时返回 null。 */
  public LocalDate nextBizDate() {
    return schedulingContext == null ? null : schedulingContext.nextBizDate();
  }

  /** 是否节假日(当前语义=周末);无调度上下文时返回 null。 */
  public Boolean isHoliday() {
    return schedulingContext == null ? null : schedulingContext.isHoliday();
  }

  /** 本次执行尝试序号(retry/reclaim 递增);无调度上下文时返回 null。 */
  public Integer attemptNo() {
    return schedulingContext == null ? null : schedulingContext.attemptNo();
  }

  /** 触发来源编码(平台暂无来源列,当前恒 null)。 */
  public String triggerCode() {
    return schedulingContext == null ? null : schedulingContext.triggerCode();
  }

  /** 所属 workflow run(平台暂无来源列,当前恒 null)。 */
  public Long workflowRunId() {
    return schedulingContext == null ? null : schedulingContext.workflowRunId();
  }
}
