package com.example.batch.orchestrator.application.engine;

/**
 * Outbox `event_key` 中心化生成器。
 *
 * <p>替代历史上 7 个调用方各自字符串拼 eventKey 的散漫做法,把"哪个场景生成什么 key"集中到一处,带 prefix 区分场景:
 *
 * <ul>
 *   <li>{@link #forDispatch} — 首次 dispatch 任务派发
 *   <li>{@link #forWorkflowNodeDispatch} — workflow DAG 节点派发
 *   <li>{@link #forFileRedispatch} — 文件治理手动重派
 *   <li>{@link #forRetry} — task 重试(含 attempt 序号防重)
 *   <li>{@link #forReclaim} — worker drain reclaim
 *   <li>{@link #forCompensation} — 补偿命令再派发
 *   <li>{@link #forWorkflowTerminal} — workflow 终态事件
 * </ul>
 *
 * <p><b>幂等保证</b>:DB 层 {@code uk_outbox_event_key UNIQUE (tenant_id, event_key)} + {@code on
 * conflict do nothing} 回退。同一场景内同一目标重复生成必产生同一 key,自动去重。
 *
 * <p><b>Format 约定</b>:{@code <tenant>:<scope>:<key parts joined by ":">},scope 名小写连字符。最大长度 256,超长由
 * {@link #truncate} 自动 trim 末尾(行级 audit / 复现以 outbox_event_id 为准,key 截断不影响幂等性 — 撞 prefix 极小概率,可接受)。
 *
 * <p><b>反例(禁止)</b>:
 *
 * <ul>
 *   <li>{@code tenantId + ":" + taskId} 裸拼 — 没 scope 前缀,易撞键
 *   <li>把 eventKey 当业务标识用 — 它仅用于 outbox 去重,业务用 idempotencyKey
 * </ul>
 */
public final class OutboxEventKeyGenerator {

  /** event_key 列宽上限(VARCHAR(256)),超长 trim 末尾。 */
  private static final int MAX_LENGTH = 256;

  private OutboxEventKeyGenerator() {}

  /** 首次 dispatch:{@code <tenant>:dispatch:<taskId>} — 同一 task 重复 dispatch 由 outbox 唯一约束去重。 */
  public static String forDispatch(String tenantId, Long taskId) {
    return build(tenantId, "dispatch", String.valueOf(taskId));
  }

  /**
   * Workflow DAG 节点 dispatch:{@code <tenant>:wf:<workflowRunId>:<nodeCode>:<taskId>}。Workflow 维度的
   * task 与首次 dispatch 区分,允许同 task 在不同 workflow run 中独立去重。
   */
  public static String forWorkflowNodeDispatch(
      String tenantId, Long workflowRunId, String nodeCode, Long taskId) {
    return build(tenantId, "wf", String.valueOf(workflowRunId), nodeCode, String.valueOf(taskId));
  }

  /** 文件治理手动重派:{@code <tenant>:file-redispatch:<taskId>}。 */
  public static String forFileRedispatch(String tenantId, Long taskId) {
    return build(tenantId, "file-redispatch", String.valueOf(taskId));
  }

  /**
   * Task 重试:{@code <tenant>:retry:<taskId>:<attempt>}。<b>必须带 attempt</b>,否则同 task 多次重试因相同 key 被
   * dedup 丢失重试事件。
   */
  public static String forRetry(String tenantId, Long taskId, int attempt) {
    return build(tenantId, "retry", String.valueOf(taskId), String.valueOf(attempt));
  }

  /**
   * 运维 manual replay(RecoveryController):{@code <tenant>:replay-<kind>:<id>}。{@code kind} 形如 {@code
   * task} / {@code partition},语义是"运维手动重播";同一 id 多次 POST 应当 dedup(operator intent 是幂等触发)。需要"强制重新触发"由
   * operator 用 IdGenerator.newBusinessNo("rpl") 加入 key 显式打破。
   */
  public static String forManualReplay(String tenantId, String kind, Long id) {
    return build(tenantId, "replay-" + kind, String.valueOf(id));
  }

  /**
   * Worker drain reclaim:{@code <tenant>:reclaim:<taskId>:<drainEventId>}。drainEventId 区分多次 drain
   * 周期(同 task 在不同 drain 周期被 reclaim 是合法重复)。
   */
  public static String forReclaim(String tenantId, Long taskId, String drainEventId) {
    return build(tenantId, "reclaim", String.valueOf(taskId), drainEventId);
  }

  /**
   * 补偿命令再派发:{@code <tenant>:cmp:<commandNo>:<taskId>}。commandNo 是
   * compensation_command.command_no,确保同 task 在不同补偿命令下独立去重。
   */
  public static String forCompensation(String tenantId, String commandNo, Long taskId) {
    return build(tenantId, "cmp", commandNo, String.valueOf(taskId));
  }

  /**
   * Workflow 终态事件:{@code <tenant>:workflow:<workflowRunId>:terminal}。同 workflow_run
   * 进入终态多次(理论上由前态守护拦掉)被 dedup 回退。
   */
  public static String forWorkflowTerminal(String tenantId, Long workflowRunId) {
    return build(tenantId, "workflow", String.valueOf(workflowRunId), "terminal");
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private static String build(String tenantId, String scope, String... parts) {
    StringBuilder sb = new StringBuilder(tenantId == null ? "" : tenantId);
    sb.append(':').append(scope);
    for (String p : parts) {
      sb.append(':').append(p == null ? "" : p);
    }
    return truncate(sb.toString());
  }

  private static String truncate(String key) {
    return key.length() <= MAX_LENGTH ? key : key.substring(0, MAX_LENGTH);
  }
}
