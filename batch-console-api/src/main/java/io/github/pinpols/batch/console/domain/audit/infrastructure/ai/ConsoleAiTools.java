package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.constants.BatchStatusConstants;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.job.web.query.JobExecutionLogQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.JobInstanceQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobExecutionLogResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobInstanceResponse;
import io.github.pinpols.batch.console.domain.notification.web.query.AlertEventQueryRequest;
import io.github.pinpols.batch.console.domain.notification.web.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.domain.observability.application.ConsoleQueryApplicationService;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleClusterDiagnosticService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Console AI 的只读诊断工具(L3 function-calling):让模型在需要时拉取「实时系统状态」回答, 把「讲概念」升级成「诊断你眼前这次失败」。
 *
 * <p><b>租户安全</b>:本对象按请求构造，{@code tenantId} 在构造时从请求上下文绑定、<b>不暴露给模型</b>; 模型只能传业务 id(如
 * jobInstanceId),所有查询强制限定在当前租户内,杜绝跨租户读取。
 *
 * <p>只读:仅调用 {@link ConsoleQueryApplicationService} 的查询方法,不改任何状态。返回值压缩成精简文本, 控制 token 与噪声。
 */
public class ConsoleAiTools {

  private final String tenantId;
  private final ConsoleQueryApplicationService queryService;
  private final ConsoleClusterDiagnosticService diagnosticService;
  private final int maxRows;

  public ConsoleAiTools(
      String tenantId,
      ConsoleQueryApplicationService queryService,
      ConsoleClusterDiagnosticService diagnosticService,
      int maxRows) {
    this.tenantId = tenantId;
    this.queryService = queryService;
    this.diagnosticService = diagnosticService;
    this.maxRows = Math.max(maxRows, 1);
  }

  @Tool(
      description =
          "查询指定 job 实例的当前状态、jobCode、失败分类(failureClass)、起止时间与结果摘要。"
              + "用户提到某个具体实例 id 或问「某次执行为什么失败」时调用。")
  public String getJobInstance(@ToolParam(description = "job 实例 id(数字)") long jobInstanceId) {
    ConsoleJobInstanceResponse instance = queryService.jobInstance(tenantId, jobInstanceId);
    if (instance == null) {
      return "未找到 job 实例:id=" + jobInstanceId + "(当前租户内不存在)";
    }
    return renderInstance(instance);
  }

  @Tool(description = "查询指定 job 实例的执行日志(按时间倒序、最多若干条),用于定位失败的具体原因 / 报错信息。")
  public String getJobExecutionLogs(@ToolParam(description = "job 实例 id(数字)") long jobInstanceId) {
    JobExecutionLogQueryRequest request = new JobExecutionLogQueryRequest();
    request.setTenantId(tenantId);
    request.setJobInstanceId(jobInstanceId);
    request.setPageNo(1);
    request.setPageSize(maxRows);
    PageResponse<ConsoleJobExecutionLogResponse> page = queryService.jobExecutionLogs(request);
    if (page.items().isEmpty()) {
      return "无执行日志:jobInstanceId=" + jobInstanceId;
    }
    return page.items().stream().map(this::renderLog).collect(Collectors.joining("\n"));
  }

  @Tool(description = "列出当前租户最近的失败(FAILED)job 实例,用于发现近期问题或找出要进一步排查的实例 id。")
  public String listRecentFailedJobInstances() {
    JobInstanceQueryRequest request = new JobInstanceQueryRequest();
    request.setTenantId(tenantId);
    request.setInstanceStatus(BatchStatusConstants.FAILED);
    request.setPageNo(1);
    request.setPageSize(maxRows);
    PageResponse<ConsoleJobInstanceResponse> page = queryService.jobInstances(request);
    if (page.items().isEmpty()) {
      return "当前租户近期无 FAILED 实例。";
    }
    return page.items().stream()
        .map(
            instance ->
                "id="
                    + instance.id()
                    + " jobCode="
                    + instance.jobCode()
                    + " failureClass="
                    + nullToDash(instance.failureClass())
                    + " finishedAt="
                    + instance.finishedAt())
        .collect(Collectors.joining("\n"));
  }

  @Tool(
      description =
          "返回当前租户的集群健康诊断快照(只读,固定阈值):ShedLock 定时任务租约、Worker 注册一致性、"
              + "Outbox 投递健康、终态实例遗留活跃子项。用于解读『任务卡住 / stuck / 不推进 / 定时任务不跑 / "
              + "worker 失联 / 事件积压』等集群面问题,判断卡点在哪一层并给处置建议。无需任何参数。")
  public String getClusterDiagnostics() {
    if (diagnosticService == null) {
      return "集群诊断服务当前不可用(诊断能力未装配)。";
    }
    Map<String, Object> diagnostics = diagnosticService.diagnose(tenantId);
    if (diagnostics == null || diagnostics.isEmpty()) {
      return "集群诊断当前无数据(可能诊断服务未就绪或该租户无相关记录)。";
    }
    Map<String, Object> shedLock = subMap(diagnostics.get("shedLock"));
    Map<String, Object> workers = subMap(diagnostics.get("workers"));
    Map<String, Object> outbox = subMap(diagnostics.get("outbox"));
    Map<String, Object> terminal = subMap(diagnostics.get("terminalChildren"));
    return "[集群诊断] 当前租户只读快照(固定阈值,超阈即判不健康)\n"
        + "ShedLock 定时任务租约: totalLocks="
        + field(shedLock, "totalLocks")
        + " activeLocks="
        + field(shedLock, "activeLocks")
        + "\nWorker 一致性: healthy="
        + field(workers, "healthy")
        + " onlineWorkers="
        + field(workers, "onlineWorkers")
        + " drainingWorkers="
        + field(workers, "drainingWorkers")
        + " offlineWorkers="
        + field(workers, "offlineWorkers")
        + " staleOnlineWorkers="
        + field(workers, "staleOnlineWorkers")
        + " drainingPastDeadlineWorkers="
        + field(workers, "drainingPastDeadlineWorkers")
        + " decommissionedWorkersWithActiveTasks="
        + field(workers, "decommissionedWorkersWithActiveTasks")
        + " invalidCapabilityTags="
        + field(workers, "invalidCapabilityTags")
        + " runningInstances="
        + field(workers, "runningInstances")
        + "\nOutbox 投递: healthy="
        + field(outbox, "healthy")
        + " pendingEvents="
        + field(outbox, "pendingEvents")
        + " activeEvents="
        + field(outbox, "activeEvents")
        + " stalePublishingEvents="
        + field(outbox, "stalePublishingEvents")
        + "\n终态子项 (terminalChildren): healthy="
        + field(terminal, "healthy")
        + " terminalInstancesWithActiveChildren="
        + field(terminal, "terminalInstancesWithActiveChildren");
  }

  @Tool(
      description =
          "列出当前租户当前未决(status=OPEN)的告警事件,用于告警分诊:按 severity(CRITICAL/ERROR/WARN/INFO)与"
              + "影响排序、对同类反复告警(看 occurrenceCount 去重归并)、概括当前告警态势并给处置/升级建议。"
              + "用户问『现在有哪些告警 / 该先处理哪个 / 告警态势 / 要不要升级』这类问题时调用。无需参数。")
  public String getOpenAlerts() {
    return renderAlerts(queryAlerts("OPEN"), "当前租户暂无未决(OPEN)告警。");
  }

  @Tool(
      description =
          "列出当前租户最近的告警事件(不限状态,含 OPEN/ACKED/SUPPRESSED/CLOSED),用于回顾近期告警态势、"
              + "识别反复出现(occurrenceCount 高)或已处置的告警。用户问『最近有哪些告警 / 告警历史 / 某类告警是不是反复出现』时调用。无需参数。")
  public String getRecentAlerts() {
    return renderAlerts(queryAlerts(null), "当前租户近期无告警记录。");
  }

  private List<ConsoleAlertEventResponse> queryAlerts(String status) {
    AlertEventQueryRequest request = new AlertEventQueryRequest();
    request.setTenantId(tenantId);
    request.setStatus(status);
    request.setPageNo(1);
    request.setPageSize(maxRows);
    PageResponse<ConsoleAlertEventResponse> page = queryService.alertEvents(request);
    return page.items();
  }

  private String renderAlerts(List<ConsoleAlertEventResponse> alerts, String emptyMessage) {
    if (alerts.isEmpty()) {
      return emptyMessage;
    }
    return alerts.stream().map(this::renderAlert).collect(Collectors.joining("\n"));
  }

  private String renderAlert(ConsoleAlertEventResponse alert) {
    return "id="
        + alert.id()
        + " alertType="
        + nullToDash(alert.alertType())
        + " severity="
        + nullToDash(alert.severity())
        + " status="
        + nullToDash(alert.status())
        + " occurrenceCount="
        + (alert.occurrenceCount() == null ? "-" : alert.occurrenceCount())
        + " firstSeenAt="
        + alert.firstSeenAt()
        + " lastSeenAt="
        + alert.lastSeenAt()
        + " traceId="
        + nullToDash(alert.traceId())
        + " | "
        + truncate(alert.title(), 200);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> subMap(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : Map.of();
  }

  private static String field(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? "-" : value.toString();
  }

  private String renderInstance(ConsoleJobInstanceResponse instance) {
    return "id="
        + instance.id()
        + "\njobCode="
        + instance.jobCode()
        + "\ninstanceNo="
        + instance.instanceNo()
        + "\nstatus="
        + instance.instanceStatus()
        + "\nfailureClass="
        + nullToDash(instance.failureClass())
        + "\nbizDate="
        + instance.bizDate()
        + "\nstartedAt="
        + instance.startedAt()
        + "\nfinishedAt="
        + instance.finishedAt()
        + "\nretryFlag="
        + instance.retryFlag()
        + "\nrerunFlag="
        + instance.rerunFlag()
        + "\ntraceId="
        + nullToDash(instance.traceId())
        + "\nresultSummary="
        + truncate(instance.resultSummary(), 500);
  }

  private String renderLog(ConsoleJobExecutionLogResponse log) {
    return "["
        + log.createdAt()
        + "] "
        + nullToDash(log.logLevel())
        + " "
        + nullToDash(log.logType())
        + " partition="
        + log.jobPartitionId()
        + " | "
        + truncate(log.message(), 500);
  }

  private static String nullToDash(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  private static String truncate(String value, int maxLength) {
    if (value == null) {
      return "-";
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
  }

  /** 暴露给上层的工具实例(列表形态便于 {@code ChatClient.tools(Object...)} 传入)。 */
  public List<Object> asToolObjects() {
    return List.of(this);
  }
}
