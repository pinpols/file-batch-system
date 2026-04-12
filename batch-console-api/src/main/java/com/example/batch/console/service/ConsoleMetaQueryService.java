package com.example.batch.console.service;

import com.example.batch.common.enums.AiPromptCategory;
import com.example.batch.common.enums.AlertSeverity;
import com.example.batch.common.enums.ApprovalCommandStatus;
import com.example.batch.common.enums.BatchWindowEndStrategy;
import com.example.batch.common.enums.CalendarDayType;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.ConfigLifecycleStatus;
import com.example.batch.common.enums.FileChannelAuthType;
import com.example.batch.common.enums.FileChannelType;
import com.example.batch.common.enums.FileReceiptPolicy;
import com.example.batch.common.enums.FileTemplateFormat;
import com.example.batch.common.enums.FileTemplateType;
import com.example.batch.common.enums.HolidayRollRule;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.OutOfWindowAction;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.PipelineType;
import com.example.batch.common.enums.QueuePriorityPolicy;
import com.example.batch.common.enums.ResourceQueueType;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.enums.WorkflowEdgeType;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.enums.WorkflowType;
import com.example.batch.console.repository.ConsoleMetaQueryRepository;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.response.ConsoleMetaEnumItem;
import com.example.batch.console.web.response.ConsoleMetaOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class ConsoleMetaQueryService {

  private final ConsoleMetaQueryRepository repository;
  private final ConsoleTenantGuard tenantGuard;

  public ConsoleMetaQueryService(
      ConsoleMetaQueryRepository repository, ConsoleTenantGuard tenantGuard) {
    this.repository = repository;
    this.tenantGuard = tenantGuard;
  }

  public Map<String, List<ConsoleMetaEnumItem>> enums() {
    Map<String, List<ConsoleMetaEnumItem>> result = new LinkedHashMap<>();

    // ── 触发 & 调度 ──────────────────────────────────────────────
    result.put("triggerType", toItems(TriggerType.values(), TriggerType::code, TriggerType::label));
    result.put(
        "scheduleType",
        List.of(item("CRON", "Cron 表达式"), item("FIXED_RATE", "固定频率"), item("MANUAL", "手动")));
    result.put(
        "triggerMode",
        List.of(
            item("SCHEDULED", "定时"),
            item("API", "API"),
            item("MANUAL", "手动"),
            item("EVENT", "事件"),
            item("MIXED", "混合")));
    result.put(
        "catchUpPolicy",
        toItems(CatchUpPolicyType.values(), CatchUpPolicyType::code, CatchUpPolicyType::label));

    // ── 作业 & 分片 ──────────────────────────────────────────────
    result.put("jobType", toItems(JobType.values(), JobType::code, JobType::label));
    result.put(
        "shardStrategy",
        toItems(ShardStrategy.values(), ShardStrategy::code, ShardStrategy::label));
    result.put(
        "retryPolicy",
        toItems(RetryPolicyType.values(), RetryPolicyType::code, RetryPolicyType::label));
    result.put("taskStatus", toItems(TaskStatus.values(), TaskStatus::code, TaskStatus::label));
    result.put(
        "partitionStatus",
        toItems(PartitionStatus.values(), PartitionStatus::code, PartitionStatus::label));
    result.put(
        "instanceStatus",
        toItems(JobInstanceStatus.values(), JobInstanceStatus::code, JobInstanceStatus::label));

    // ── 工作流 ───────────────────────────────────────────────────
    result.put(
        "workflowType", toItems(WorkflowType.values(), WorkflowType::code, WorkflowType::label));
    result.put(
        "workflowNodeType",
        toItems(WorkflowNodeType.values(), WorkflowNodeType::code, WorkflowNodeType::label));
    result.put(
        "edgeType",
        toItems(WorkflowEdgeType.values(), WorkflowEdgeType::code, WorkflowEdgeType::label));
    result.put(
        "workflowRunStatus",
        toItems(WorkflowRunStatus.values(), WorkflowRunStatus::code, WorkflowRunStatus::label));

    // ── 流水线 ───────────────────────────────────────────────────
    result.put(
        "pipelineType", toItems(PipelineType.values(), PipelineType::code, PipelineType::label));

    // ── 文件通道 ─────────────────────────────────────────────────
    result.put(
        "channelType",
        toItems(FileChannelType.values(), FileChannelType::code, FileChannelType::label));
    result.put(
        "authType",
        toItems(
            FileChannelAuthType.values(), FileChannelAuthType::code, FileChannelAuthType::label));
    result.put(
        "receiptPolicy",
        toItems(FileReceiptPolicy.values(), FileReceiptPolicy::code, FileReceiptPolicy::label));

    // ── 文件模板 ─────────────────────────────────────────────────
    result.put(
        "fileTemplateType",
        toItems(FileTemplateType.values(), FileTemplateType::code, FileTemplateType::label));
    result.put(
        "fileTemplateFormat",
        toItems(FileTemplateFormat.values(), FileTemplateFormat::code, FileTemplateFormat::label));

    // ── 批量窗口 ─────────────────────────────────────────────────
    result.put(
        "endStrategy",
        toItems(
            BatchWindowEndStrategy.values(),
            BatchWindowEndStrategy::code,
            BatchWindowEndStrategy::label));
    result.put(
        "outOfWindowAction",
        toItems(OutOfWindowAction.values(), OutOfWindowAction::code, OutOfWindowAction::label));

    // ── 业务日历 ─────────────────────────────────────────────────
    result.put(
        "holidayStrategy",
        toItems(HolidayRollRule.values(), HolidayRollRule::code, HolidayRollRule::label));
    result.put(
        "dayType",
        toItems(CalendarDayType.values(), CalendarDayType::code, CalendarDayType::label));

    // ── 资源队列 ─────────────────────────────────────────────────
    result.put(
        "queueType",
        toItems(ResourceQueueType.values(), ResourceQueueType::code, ResourceQueueType::label));
    result.put(
        "priorityPolicy",
        toItems(
            QueuePriorityPolicy.values(), QueuePriorityPolicy::code, QueuePriorityPolicy::label));

    // ── 告警 ─────────────────────────────────────────────────────
    result.put(
        "severity", toItems(AlertSeverity.values(), AlertSeverity::code, AlertSeverity::label));
    result.put(
        "alertStatus",
        List.of(
            item("OPEN", "待处理"),
            item("ACKED", "已确认"),
            item("SUPPRESSED", "已抑制"),
            item("CLOSED", "已关闭")));

    // ── 审批 & 配置发布 ──────────────────────────────────────────
    result.put(
        "approvalStatus",
        toItems(
            ApprovalCommandStatus.values(),
            ApprovalCommandStatus::code,
            ApprovalCommandStatus::label));
    result.put(
        "approvalType",
        List.of(
            item("CATCH_UP", "补跑"),
            item("COMPENSATION", "补偿"),
            item("DLQ_REPLAY", "死信重放"),
            item("DOWNLOAD", "下载")));
    result.put(
        "configStatus",
        toItems(
            ConfigLifecycleStatus.values(),
            ConfigLifecycleStatus::code,
            ConfigLifecycleStatus::label));

    // ── Worker ───────────────────────────────────────────────────
    result.put(
        "workerStatus",
        toItems(
            WorkerRegistryStatus.values(),
            WorkerRegistryStatus::code,
            WorkerRegistryStatus::label));

    // ── Outbox ───────────────────────────────────────────────────
    result.put(
        "outboxPublishStatus",
        toItems(
            OutboxPublishStatus.values(), OutboxPublishStatus::code, OutboxPublishStatus::label));

    // ── AI ───────────────────────────────────────────────────────
    result.put(
        "aiPromptCategory",
        toItems(AiPromptCategory.values(), AiPromptCategory::code, AiPromptCategory::label));

    // ── 文件审计 ─────────────────────────────────────────────────
    result.put(
        "operationType",
        List.of(
            item("ARRIVAL_REGISTER", "到达登记"),
            item("RECEIVE_SCAN", "接收扫描"),
            item("IMPORT_FEEDBACK", "导入反馈"),
            item("BAD_RECORD_GOVERNANCE", "坏记录治理"),
            item("EXPORT_REGISTER", "导出登记"),
            item("EXPORT_COMPLETE", "导出完成"),
            item("DISPATCH_COMPLETE", "分发完成"),
            item("DISPATCH_COMPENSATE", "分发补偿"),
            item("CATCH_UP_APPROVAL", "补跑审批"),
            item("BATCH_DAY_CATCH_UP", "批次日补跑")));
    result.put("operationResult", List.of(item("SUCCESS", "成功"), item("FAILED", "失败")));

    // ── 文件状态 ─────────────────────────────────────────────────
    result.put(
        "fileStatus",
        List.of(
            item("RECEIVED", "已接收"),
            item("PARSING", "解析中"),
            item("PARSED", "已解析"),
            item("VALIDATED", "已校验"),
            item("LOADED", "已加载"),
            item("GENERATED", "已生成"),
            item("DISPATCHING", "分发中"),
            item("DISPATCHED", "已分发"),
            item("ARCHIVED", "已归档"),
            item("FAILED", "失败"),
            item("DELETED", "已删除")));

    return result;
  }

  public List<ConsoleMetaOption> queues(String tenantId) {
    return toOptions(repository.queueOptions(tenantGuard.resolveTenant(tenantId)));
  }

  public List<ConsoleMetaOption> calendars(String tenantId) {
    return toOptions(repository.calendarOptions(tenantGuard.resolveTenant(tenantId)));
  }

  public List<ConsoleMetaOption> windows(String tenantId) {
    return toOptions(repository.windowOptions(tenantGuard.resolveTenant(tenantId)));
  }

  public List<ConsoleMetaOption> workerGroups(String tenantId) {
    return toOptions(repository.workerGroupOptions(tenantGuard.resolveTenant(tenantId)));
  }

  public List<ConsoleMetaOption> bizTypes(String tenantId) {
    return toOptions(repository.bizTypeOptions(tenantGuard.resolveTenant(tenantId)));
  }

  private List<ConsoleMetaOption> toOptions(
      List<ConsoleMetaQueryRepository.SimpleOptionView> rows) {
    return rows.stream().map(row -> new ConsoleMetaOption(row.getCode(), row.getLabel())).toList();
  }

  private static <E> List<ConsoleMetaEnumItem> toItems(
      E[] values, Function<E, String> code, Function<E, String> label) {
    return Arrays.stream(values)
        .map(e -> new ConsoleMetaEnumItem(code.apply(e), label.apply(e)))
        .toList();
  }

  private static ConsoleMetaEnumItem item(String code, String label) {
    return new ConsoleMetaEnumItem(code, label);
  }
}
