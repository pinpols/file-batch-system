package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.constants.BatchStatusConstants;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.job.web.query.JobExecutionLogQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.JobInstanceQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobExecutionLogResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobInstanceResponse;
import io.github.pinpols.batch.console.domain.observability.application.ConsoleQueryApplicationService;
import java.util.List;
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
  private final int maxRows;

  public ConsoleAiTools(String tenantId, ConsoleQueryApplicationService queryService, int maxRows) {
    this.tenantId = tenantId;
    this.queryService = queryService;
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
