package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.ScheduleType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * ADR-026 §三层粒度 演练计划服务实现。priority-scope §ADR-026 红线：
 *
 * <ul>
 *   <li>L1 CONFIG_VALIDATE — 解析 cron / DAG / 参数 schema；只读 + 不调外
 *   <li>L2 SCHEDULE_PLAN — 复用 SchedulePlanBuilder 算预计 partition / worker route，但不写 instance
 *   <li>L3 EXECUTION_PLAN — 在 L2 基础上叠加 task 级 SQL explain / 文件路径 / endpoint reachability stub
 * </ul>
 *
 * <p><b>不做（FULL_SIMULATION 红线）</b>：真执行 + 事务回滚 / 真写文件后删 / 真发 Kafka 不消费。
 */
@Slf4j
@Service
@SuppressWarnings("PMD.ExcessiveParameterList")
public class DefaultDryRunPlanService implements DryRunPlanService {

  /** L3 endpoint reachability HEAD timeout — 短超时避免 dry-run 长期停滞。 */
  private static final Duration HTTP_PROBE_TIMEOUT = Duration.ofSeconds(5);

  private static final String SCOPE_JOB = "job";
  private static final String SCOPE_EXECUTION = "execution";

  /** L3 effectiveParams 中可能的 SQL key 候选；命中即跑 EXPLAIN。 */
  private static final Set<String> SQL_PARAM_KEYS =
      Set.of("sql", "querySql", "sourceQuery", "validationSql", "selectSql");

  /** L3 effectiveParams 中可能的 endpoint URL key 候选；命中即跑 HTTP HEAD。 */
  private static final Set<String> ENDPOINT_PARAM_KEYS =
      Set.of("endpointUrl", "callbackUrl", "channelEndpoint", "dispatchTarget");

  /** S3 bucket 命名规则（DNS-style：3-63 字符，小写字母 / 数字 / `-`，不能 `-` 起止）。 */
  private static final Pattern S3_BUCKET_PATTERN =
      Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");

  private final OrchestratorConfigCacheService configCacheService;
  private final SchedulePlanBuilder schedulePlanBuilder;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final BatchTimezoneProvider timezoneProvider;
  private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
  private final ObjectProvider<S3Client> s3ClientProvider;
  private final ObjectProvider<S3StorageProperties> s3PropertiesProvider;
  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(HTTP_PROBE_TIMEOUT).build();

  public DefaultDryRunPlanService(
      OrchestratorConfigCacheService configCacheService,
      SchedulePlanBuilder schedulePlanBuilder,
      WorkflowNodeMapper workflowNodeMapper,
      WorkflowEdgeMapper workflowEdgeMapper,
      BatchTimezoneProvider timezoneProvider,
      ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
      ObjectProvider<S3Client> s3ClientProvider,
      ObjectProvider<S3StorageProperties> s3PropertiesProvider) {
    this.configCacheService = configCacheService;
    this.schedulePlanBuilder = schedulePlanBuilder;
    this.workflowNodeMapper = workflowNodeMapper;
    this.workflowEdgeMapper = workflowEdgeMapper;
    this.timezoneProvider = timezoneProvider;
    this.jdbcTemplateProvider = jdbcTemplateProvider;
    this.s3ClientProvider = s3ClientProvider;
    this.s3PropertiesProvider = s3PropertiesProvider;
  }

  @Override
  public DryRunPlanResult plan(DryRunPlanRequest request) {
    requireRequest(request);
    DryRunLevel level = request.level() == null ? DryRunLevel.CONFIG_VALIDATE : request.level();
    return switch (level) {
      case CONFIG_VALIDATE -> validateConfig(request);
      case SCHEDULE_PLAN -> planSchedule(request);
      case EXECUTION_PLAN -> planExecution(request);
    };
  }

  // ── L1 ──────────────────────────────────────────────────────────────────
  private DryRunPlanResult validateConfig(DryRunPlanRequest request) {
    List<DryRunFinding> findings = new ArrayList<>();
    Map<String, Object> summary = new LinkedHashMap<>();
    JobDefinitionEntity jobDef = loadJobDefinition(request, findings);
    WorkflowDefinitionEntity wfDef = loadWorkflowDefinition(request);
    if (jobDef == null && wfDef == null) {
      // job_definition 缺失已记 ERROR，直接返回
      return DryRunPlanResult.of(DryRunLevel.CONFIG_VALIDATE, findings, summary);
    }
    if (jobDef != null) {
      validateJobSchedule(jobDef, findings);
      validateJobParams(jobDef, request, findings);
      summary.put("jobCode", jobDef.jobCode());
      summary.put("scheduleType", jobDef.scheduleType());
      summary.put("scheduleExpr", jobDef.scheduleExpr());
    }
    if (wfDef != null) {
      validateWorkflowGraph(wfDef, findings, summary);
    }
    return DryRunPlanResult.of(DryRunLevel.CONFIG_VALIDATE, findings, summary);
  }

  private void validateJobSchedule(JobDefinitionEntity job, List<DryRunFinding> findings) {
    String type = job.scheduleType();
    String expr = job.scheduleExpr();
    if (!Texts.hasText(type)) {
      findings.add(
          DryRunFinding.error(
              "JOB_SCHEDULE_TYPE_MISSING", SCOPE_JOB, "scheduleType is required", null));
      return;
    }
    String upper = type.trim().toUpperCase(Locale.ROOT);
    if (ScheduleType.MANUAL.code().equals(upper)) {
      findings.add(
          DryRunFinding.pass("JOB_SCHEDULE_OK", SCOPE_JOB, "MANUAL trigger; no expr needed"));
      return;
    }
    if (!Texts.hasText(expr)) {
      findings.add(
          DryRunFinding.error(
              "JOB_SCHEDULE_EXPR_MISSING",
              SCOPE_JOB,
              "scheduleExpr is required for " + upper,
              null));
      return;
    }
    try {
      ZoneId zone = timezoneProvider.resolveOrDefault(job.timezone());
      if (ScheduleType.CRON.code().equals(upper)) {
        CronExpression cron = CronExpression.parse(expr);
        ZonedDateTime nextZdt = cron.next(ZonedDateTime.now(zone));
        Instant next = nextZdt == null ? null : nextZdt.toInstant();
        findings.add(
            DryRunFinding.pass("JOB_CRON_OK", SCOPE_JOB, "cron next fire computed: " + next));
      } else if (ScheduleType.FIXED_RATE.code().equals(upper)) {
        Duration d = parseFixedRate(expr);
        if (d == null || d.isZero() || d.isNegative()) {
          findings.add(
              DryRunFinding.error(
                  "JOB_FIXED_RATE_INVALID",
                  SCOPE_JOB,
                  "fixed-rate expr unparsable: " + expr,
                  expr));
        } else {
          findings.add(DryRunFinding.pass("JOB_FIXED_RATE_OK", SCOPE_JOB, "interval=" + d));
        }
      } else {
        findings.add(
            DryRunFinding.warn(
                "JOB_SCHEDULE_TYPE_UNKNOWN", SCOPE_JOB, "unknown scheduleType: " + upper, upper));
      }
    } catch (RuntimeException ex) {
      findings.add(
          DryRunFinding.error(
              "JOB_SCHEDULE_EXPR_INVALID",
              SCOPE_JOB,
              "schedule expression invalid: " + ex.getMessage(),
              expr));
    }
  }

  private void validateJobParams(
      JobDefinitionEntity job, DryRunPlanRequest request, List<DryRunFinding> findings) {
    Map<String, Object> schema = job.paramSchema();
    Map<String, Object> provided =
        request.params() == null ? Map.of() : new LinkedHashMap<>(request.params());
    if (schema == null || schema.isEmpty()) {
      findings.add(
          DryRunFinding.pass("JOB_PARAMS_NO_SCHEMA", SCOPE_JOB, "no paramSchema defined; skip"));
      return;
    }
    Object requiredObj = schema.get("required");
    if (!(requiredObj instanceof List<?> required)) {
      findings.add(DryRunFinding.pass("JOB_PARAMS_OK", SCOPE_JOB, "schema has no required clause"));
      return;
    }
    List<String> missing = new ArrayList<>();
    for (Object key : required) {
      String name = String.valueOf(key);
      if (!provided.containsKey(name) && !defaultParamsHas(job, name)) {
        missing.add(name);
      }
    }
    if (missing.isEmpty()) {
      findings.add(DryRunFinding.pass("JOB_PARAMS_OK", SCOPE_JOB, "all required params present"));
    } else {
      findings.add(
          DryRunFinding.error("JOB_PARAMS_MISSING", SCOPE_JOB, "missing required params", missing));
    }
  }

  private boolean defaultParamsHas(JobDefinitionEntity job, String name) {
    return job.defaultParams() != null && job.defaultParams().containsKey(name);
  }

  private void validateWorkflowGraph(
      WorkflowDefinitionEntity wf, List<DryRunFinding> findings, Map<String, Object> summary) {
    List<WorkflowNodeEntity> nodes = workflowNodeMapper.selectByWorkflowDefinitionId(wf.id());
    List<WorkflowEdgeEntity> edges = workflowEdgeMapper.selectAllByWorkflowDefinitionId(wf.id());
    if (nodes == null || nodes.isEmpty()) {
      findings.add(
          DryRunFinding.error("WF_NODES_EMPTY", "workflow", "workflow has no nodes", wf.id()));
      return;
    }
    boolean hasStart = nodes.stream().anyMatch(n -> "START".equalsIgnoreCase(n.getNodeType()));
    boolean hasEnd = nodes.stream().anyMatch(n -> "END".equalsIgnoreCase(n.getNodeType()));
    if (!hasStart) {
      findings.add(
          DryRunFinding.error("WF_NO_START", "workflow", "workflow missing START node", null));
    }
    if (!hasEnd) {
      findings.add(DryRunFinding.warn("WF_NO_END", "workflow", "workflow missing END node", null));
    }
    Set<String> nodeCodes =
        nodes.stream().map(WorkflowNodeEntity::getNodeCode).collect(Collectors.toSet());
    if (edges != null) {
      for (WorkflowEdgeEntity edge : edges) {
        if (!nodeCodes.contains(edge.getFromNodeCode())) {
          findings.add(
              DryRunFinding.error(
                  "WF_EDGE_FROM_DANGLING",
                  "workflow",
                  "edge fromNode not in nodes: " + edge.getFromNodeCode(),
                  edge.getFromNodeCode()));
        }
        if (!nodeCodes.contains(edge.getToNodeCode())) {
          findings.add(
              DryRunFinding.error(
                  "WF_EDGE_TO_DANGLING",
                  "workflow",
                  "edge toNode not in nodes: " + edge.getToNodeCode(),
                  edge.getToNodeCode()));
        }
      }
    }
    summary.put("workflowCode", wf.workflowCode());
    summary.put("nodeCount", nodes.size());
    summary.put("edgeCount", edges == null ? 0 : edges.size());
    if (findings.stream().noneMatch(f -> f.severity() == DryRunFinding.Severity.ERROR)) {
      findings.add(
          DryRunFinding.pass(
              "WF_GRAPH_OK", "workflow", "graph nodes/edges resolved without dangling refs"));
    }
  }

  // ── L2 ──────────────────────────────────────────────────────────────────
  private DryRunPlanResult planSchedule(DryRunPlanRequest request) {
    List<DryRunFinding> findings = new ArrayList<>();
    Map<String, Object> summary = new LinkedHashMap<>();
    if (request.bizDate() == null) {
      findings.add(
          DryRunFinding.error("BIZDATE_MISSING", "schedule", "bizDate is required for L2", null));
      return DryRunPlanResult.of(DryRunLevel.SCHEDULE_PLAN, findings, summary);
    }
    JobDefinitionEntity jobDef = loadJobDefinition(request, findings);
    if (jobDef == null) {
      return DryRunPlanResult.of(DryRunLevel.SCHEDULE_PLAN, findings, summary);
    }
    SchedulePlan plan;
    try {
      plan =
          schedulePlanBuilder.build(
              new SchedulePlanCommand(
                  request.tenantId(),
                  request.jobCode(),
                  request.bizDate().toString(),
                  request.params() == null ? Map.of() : request.params()));
    } catch (RuntimeException ex) {
      findings.add(
          DryRunFinding.error(
              "SCHEDULE_PLAN_FAILED", "schedule", "plan build failed: " + ex.getMessage(), null));
      return DryRunPlanResult.of(DryRunLevel.SCHEDULE_PLAN, findings, summary);
    }
    summary.put("queueCode", plan.getQueueCode());
    summary.put("workerGroup", plan.getWorkerGroup());
    summary.put("workerType", plan.getDefaultWorkerType());
    summary.put("priority", plan.getPriority());
    summary.put("partitionCount", plan.getPartitionCount());
    summary.put("partitions", plan.getPartitions().size());
    findings.add(
        DryRunFinding.pass(
            "SCHEDULE_PLAN_OK",
            "schedule",
            "expected 1 instance / "
                + plan.getPartitions().size()
                + " partitions on workerGroup="
                + plan.getWorkerGroup()));
    return DryRunPlanResult.of(DryRunLevel.SCHEDULE_PLAN, findings, summary);
  }

  // ── L3 ──────────────────────────────────────────────────────────────────
  private DryRunPlanResult planExecution(DryRunPlanRequest request) {
    DryRunPlanResult schedule = planSchedule(request);
    if (!schedule.success()) {
      return new DryRunPlanResult(
          DryRunLevel.EXECUTION_PLAN, false, schedule.findings(), schedule.summary());
    }
    List<DryRunFinding> findings = new ArrayList<>(schedule.findings());
    Map<String, Object> summary = new LinkedHashMap<>(schedule.summary());
    Map<String, Object> params = request.params() == null ? Map.of() : request.params();

    int sqlProbed = probeSqlExplain(params, findings);
    int s3Probed = probeS3Bucket(params, findings);
    int endpointProbed = probeEndpointReachability(params, findings);
    summary.put("l3SqlProbed", sqlProbed);
    summary.put("l3S3Probed", s3Probed);
    summary.put("l3EndpointProbed", endpointProbed);
    if (sqlProbed + s3Probed + endpointProbed == 0) {
      findings.add(
          DryRunFinding.pass(
              "EXEC_PLAN_NO_PROBES_TRIGGERED",
              SCOPE_EXECUTION,
              "no SQL / S3 / endpoint params to probe; L3 reduces to L2 result"));
    }
    return DryRunPlanResult.of(DryRunLevel.EXECUTION_PLAN, findings, summary);
  }

  /**
   * R7-A1-P0：拒绝任何以 {@code EXPLAIN } / {@code EXPLAIN(} 起头的 payload。
   *
   * <p>原因：PG 下 {@code EXPLAIN (ANALYZE) <DML>} 会真正执行 DML/DDL；如果调用方提交 {@code params.sql = "(ANALYZE)
   * DELETE FROM job_definition"}，本服务拼出来就是 {@code "EXPLAIN (ANALYZE) DELETE FROM
   * job_definition"}，DELETE 真删表。
   *
   * <p>除了拒绝 EXPLAIN 关键字，还使用 {@code EXPLAIN (ANALYZE FALSE, COSTS FALSE)} 显式 双保险：即使后续语法变更，ANALYZE
   * FALSE 也强制 planner 只计划不执行。
   */
  private static final Pattern EXPLAIN_PREFIX =
      Pattern.compile("^\\s*EXPLAIN\\b", Pattern.CASE_INSENSITIVE);

  // 纵深防御:dry-run 探测的 SQL 只允许 SELECT / WITH(CTE)开头。EXPLAIN (ANALYZE FALSE) 虽已是
  // planner-only,但那是对 PG 实现细节的单层依赖(个别语句如 CREATE INDEX CONCURRENTLY / REFRESH
  // MAT VIEW 历史上有越过 ANALYZE 标志真执行的分支)。此处先做语句类型白名单,把 DML/DDL 挡在
  // EXPLAIN 之前。用首关键字而非 AST 解析:合法探测 SQL 本就是 SELECT/WITH,零误伤;WITH..DML 这类
  // CTE 内嵌 DML 仍由下游 ANALYZE FALSE 回退。注释/前导空白由 \\s* + 关键字边界覆盖。
  private static final Pattern SELECT_OR_WITH_PREFIX =
      Pattern.compile("^\\s*(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE);

  /** L3-1: 对 params 中匹配 SQL_PARAM_KEYS 的字符串调用 EXPLAIN(ANALYZE FALSE)，仅做计划探测。 */
  private int probeSqlExplain(Map<String, Object> params, List<DryRunFinding> findings) {
    JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    if (jdbcTemplate == null) return 0;
    int probed = 0;
    for (String key : SQL_PARAM_KEYS) {
      Object raw = params.get(key);
      if (!(raw instanceof String sql) || !Texts.hasText(sql)) continue;
      probed++;
      String trimmed = sql.trim();
      if (EXPLAIN_PREFIX.matcher(trimmed).find()) {
        // R7-A1-P0：拒绝调用方手动塞 EXPLAIN（含 EXPLAIN (ANALYZE) <DML>），避免被嵌套执行 DML。
        findings.add(
            DryRunFinding.error(
                "EXEC_SQL_EXPLAIN_REJECTED",
                SCOPE_EXECUTION,
                "payload starts with EXPLAIN — refusing to nest a second EXPLAIN; submit raw"
                    + " SELECT",
                key));
        continue;
      }
      if (!SELECT_OR_WITH_PREFIX.matcher(trimmed).find()) {
        // 纵深防御:非 SELECT/WITH(即 DML/DDL)在进入 EXPLAIN 之前直接拒绝,不依赖 ANALYZE FALSE 回退。
        findings.add(
            DryRunFinding.error(
                "EXEC_SQL_NON_SELECT_REJECTED",
                SCOPE_EXECUTION,
                "dry-run SQL probe only accepts SELECT / WITH statements; refusing to EXPLAIN a"
                    + " DML/DDL payload",
                key));
        continue;
      }
      try {
        // R7-A1-P0：ANALYZE FALSE + COSTS FALSE 显式强制 planner-only，零侧效；
        // 即使 SQL 是 DML 也不会真正执行（与裸 EXPLAIN 不同，可对抗未来 PG 默认行为变化）。
        jdbcTemplate.execute("EXPLAIN (ANALYZE FALSE, COSTS FALSE) " + trimmed);
        findings.add(
            DryRunFinding.pass(
                "EXEC_SQL_EXPLAIN_OK", SCOPE_EXECUTION, "EXPLAIN passed for " + key));
      } catch (RuntimeException ex) {
        findings.add(
            DryRunFinding.error(
                "EXEC_SQL_EXPLAIN_FAILED",
                SCOPE_EXECUTION,
                "EXPLAIN failed for " + key + ": " + ex.getMessage(),
                key));
      }
    }
    return probed;
  }

  /**
   * L3-2: S3 bucket 探测。
   *
   * <ul>
   *   <li>若 params.s3Bucket 缺失，回退到 S3StorageProperties.bucket 默认；
   *   <li>校验 bucket 命名合法（DNS-style）；
   *   <li>若 S3Client 可用，调用 headBucket；不可用降级为只校验命名规则。
   * </ul>
   */
  private int probeS3Bucket(Map<String, Object> params, List<DryRunFinding> findings) {
    String bucket = stringValue(params, "s3Bucket");
    if (!Texts.hasText(bucket)) {
      S3StorageProperties props = s3PropertiesProvider.getIfAvailable();
      bucket = props == null ? null : props.getBucket();
    }
    if (!Texts.hasText(bucket)) return 0;
    if (!S3_BUCKET_PATTERN.matcher(bucket).matches()) {
      findings.add(
          DryRunFinding.error(
              "EXEC_S3_BUCKET_INVALID",
              SCOPE_EXECUTION,
              "s3 bucket name does not match DNS-style rule: " + bucket,
              bucket));
      return 1;
    }
    S3Client client = s3ClientProvider.getIfAvailable();
    if (client == null) {
      findings.add(
          DryRunFinding.warn(
              "EXEC_S3_CLIENT_UNAVAILABLE",
              SCOPE_EXECUTION,
              "S3Client bean unavailable; bucket name passed regex only",
              bucket));
      return 1;
    }
    try {
      boolean exists;
      try {
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        exists = true;
      } catch (NoSuchBucketException notFound) {
        exists = false;
      }
      if (exists) {
        findings.add(
            DryRunFinding.pass(
                "EXEC_S3_BUCKET_OK", SCOPE_EXECUTION, "s3 bucket exists: " + bucket));
      } else {
        findings.add(
            DryRunFinding.error(
                "EXEC_S3_BUCKET_MISSING",
                SCOPE_EXECUTION,
                "s3 bucket not found: " + bucket,
                bucket));
      }
    } catch (Exception ex) {
      findings.add(
          DryRunFinding.warn(
              "EXEC_S3_PROBE_FAILED",
              SCOPE_EXECUTION,
              "s3 probe failed: " + ex.getMessage(),
              bucket));
    }
    return 1;
  }

  /** L3-3: 对 params 中匹配 ENDPOINT_PARAM_KEYS 的 URL 做 HTTP HEAD（5s timeout，失败 WARN 不阻断）。 */
  private int probeEndpointReachability(Map<String, Object> params, List<DryRunFinding> findings) {
    int probed = 0;
    for (String key : ENDPOINT_PARAM_KEYS) {
      Object raw = params.get(key);
      if (!(raw instanceof String url) || !Texts.hasText(url)) continue;
      probed++;
      String trimmed = url.trim();
      if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        findings.add(
            DryRunFinding.warn(
                "EXEC_ENDPOINT_NON_HTTP",
                SCOPE_EXECUTION,
                "endpoint not http/https; reachability probe skipped: " + key,
                trimmed));
        continue;
      }
      try {
        HttpRequest req =
            HttpRequest.newBuilder(URI.create(trimmed))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(HTTP_PROBE_TIMEOUT)
                .build();
        HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        int status = resp.statusCode();
        if (status >= 200 && status < 500) {
          findings.add(
              DryRunFinding.pass(
                  "EXEC_ENDPOINT_OK",
                  SCOPE_EXECUTION,
                  key + " reachable; HEAD returned " + status));
        } else {
          findings.add(
              DryRunFinding.warn(
                  "EXEC_ENDPOINT_5XX", SCOPE_EXECUTION, key + " HEAD returned " + status, trimmed));
        }
      } catch (Exception ex) {
        findings.add(
            DryRunFinding.warn(
                "EXEC_ENDPOINT_UNREACHABLE",
                SCOPE_EXECUTION,
                key + " probe failed: " + ex.getMessage(),
                trimmed));
      }
    }
    return probed;
  }

  private static String stringValue(Map<String, Object> params, String key) {
    Object raw = params == null ? null : params.get(key);
    return raw instanceof String s ? s : null;
  }

  // ── helpers ─────────────────────────────────────────────────────────────
  private JobDefinitionEntity loadJobDefinition(
      DryRunPlanRequest request, List<DryRunFinding> findings) {
    JobDefinitionEntity jobDef =
        configCacheService.findEnabledJobDefinition(request.tenantId(), request.jobCode());
    if (jobDef == null) {
      findings.add(
          DryRunFinding.error(
              "JOB_DEFINITION_NOT_FOUND",
              SCOPE_JOB,
              "job_definition not found or disabled",
              request.jobCode()));
    }
    return jobDef;
  }

  private WorkflowDefinitionEntity loadWorkflowDefinition(DryRunPlanRequest request) {
    return configCacheService.findEnabledWorkflowDefinition(request.tenantId(), request.jobCode());
  }

  private void requireRequest(DryRunPlanRequest request) {
    if (request == null || !Texts.hasText(request.tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.dryrun.tenant_required");
    }
    if (!Texts.hasText(request.jobCode())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.dryrun.job_code_required");
    }
  }

  private static Duration parseFixedRate(String expr) {
    String trimmed = expr.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.chars().allMatch(Character::isDigit)) {
      long seconds = Long.parseLong(trimmed);
      return seconds <= 0 ? null : Duration.ofSeconds(seconds);
    }
    try {
      return Duration.parse(trimmed.toUpperCase(Locale.ROOT));
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}
