package com.example.batch.console.service;

import com.example.batch.console.repository.ConsoleMetaQueryRepository;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.ConsoleMetaController;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConsoleMetaQueryService {

    private final ConsoleMetaQueryRepository repository;
    private final ConsoleTenantGuard tenantGuard;

    public ConsoleMetaQueryService(
            ConsoleMetaQueryRepository repository, ConsoleTenantGuard tenantGuard) {
        this.repository = repository;
        this.tenantGuard = tenantGuard;
    }

    public Map<String, List<ConsoleMetaController.EnumItem>> enums() {
        Map<String, List<ConsoleMetaController.EnumItem>> result = new LinkedHashMap<>();
        result.put("triggerType", enumItems(com.example.batch.common.enums.TriggerType.values()));
        result.put(
                "jobType",
                List.of(
                        new ConsoleMetaController.EnumItem("GENERAL", "通用作业"),
                        new ConsoleMetaController.EnumItem("IMPORT", "导入"),
                        new ConsoleMetaController.EnumItem("EXPORT", "导出"),
                        new ConsoleMetaController.EnumItem("DISPATCH", "派发"),
                        new ConsoleMetaController.EnumItem("WORKFLOW", "工作流")));
        result.put(
                "scheduleType",
                List.of(
                        new ConsoleMetaController.EnumItem("CRON", "Cron 表达式"),
                        new ConsoleMetaController.EnumItem("FIXED_RATE", "固定频率"),
                        new ConsoleMetaController.EnumItem("MANUAL", "手动"),
                        new ConsoleMetaController.EnumItem("EVENT", "事件触发"),
                        new ConsoleMetaController.EnumItem("ONE_TIME", "一次性")));
        result.put(
                "triggerMode",
                List.of(
                        new ConsoleMetaController.EnumItem("SCHEDULED", "定时"),
                        new ConsoleMetaController.EnumItem("API", "API"),
                        new ConsoleMetaController.EnumItem("MANUAL", "手动"),
                        new ConsoleMetaController.EnumItem("EVENT", "事件"),
                        new ConsoleMetaController.EnumItem("MIXED", "混合")));
        result.put(
                "shardStrategy",
                List.of(
                        new ConsoleMetaController.EnumItem("NONE", "不分片"),
                                new ConsoleMetaController.EnumItem("STATIC", "静态分片"),
                        new ConsoleMetaController.EnumItem("DYNAMIC", "动态分片"),
                                new ConsoleMetaController.EnumItem("AUTO", "自动")));
        result.put(
                "retryPolicy",
                List.of(
                        new ConsoleMetaController.EnumItem("NONE", "不重试"),
                        new ConsoleMetaController.EnumItem("FIXED", "固定间隔"),
                        new ConsoleMetaController.EnumItem("EXPONENTIAL", "指数退避")));
        result.put(
                "instanceStatus",
                List.of(
                        new ConsoleMetaController.EnumItem("CREATED", "已创建"),
                        new ConsoleMetaController.EnumItem("WAITING", "等待中"),
                        new ConsoleMetaController.EnumItem("READY", "就绪"),
                        new ConsoleMetaController.EnumItem("RUNNING", "运行中"),
                        new ConsoleMetaController.EnumItem("SUCCESS", "成功"),
                        new ConsoleMetaController.EnumItem("PARTIAL_FAILED", "部分失败"),
                        new ConsoleMetaController.EnumItem("FAILED", "失败"),
                        new ConsoleMetaController.EnumItem("CANCELLED", "已取消"),
                        new ConsoleMetaController.EnumItem("TERMINATED", "已终止")));
        result.put(
                "workflowNodeType",
                List.of(
                        new ConsoleMetaController.EnumItem("START", "开始"),
                        new ConsoleMetaController.EnumItem("END", "结束"),
                        new ConsoleMetaController.EnumItem("JOB", "作业"),
                        new ConsoleMetaController.EnumItem("PIPELINE", "流水线"),
                        new ConsoleMetaController.EnumItem("CONDITION", "条件"),
                        new ConsoleMetaController.EnumItem("FORK", "分支"),
                        new ConsoleMetaController.EnumItem("JOIN", "汇聚")));
        result.put(
                "channelType",
                List.of(
                        new ConsoleMetaController.EnumItem("SFTP", "SFTP"),
                        new ConsoleMetaController.EnumItem("S3", "S3"),
                        new ConsoleMetaController.EnumItem("HTTP", "HTTP"),
                        new ConsoleMetaController.EnumItem("LOCAL", "本地"),
                        new ConsoleMetaController.EnumItem("API_PUSH", "API推送")));
        return result;
    }

    public List<ConsoleMetaController.SimpleOption> queues(String tenantId) {
        return toOptions(repository.queueOptions(tenantGuard.resolveTenant(tenantId)));
    }

    public List<ConsoleMetaController.SimpleOption> calendars(String tenantId) {
        return toOptions(repository.calendarOptions(tenantGuard.resolveTenant(tenantId)));
    }

    public List<ConsoleMetaController.SimpleOption> windows(String tenantId) {
        return toOptions(repository.windowOptions(tenantGuard.resolveTenant(tenantId)));
    }

    public List<ConsoleMetaController.SimpleOption> workerGroups(String tenantId) {
        return toOptions(repository.workerGroupOptions(tenantGuard.resolveTenant(tenantId)));
    }

    private List<ConsoleMetaController.SimpleOption> toOptions(
            List<ConsoleMetaQueryRepository.SimpleOptionView> rows) {
        return rows.stream()
                .map(row -> new ConsoleMetaController.SimpleOption(row.getCode(), row.getLabel()))
                .toList();
    }

    private static List<ConsoleMetaController.EnumItem> enumItems(
            com.example.batch.common.enums.TriggerType[] values) {
        return Arrays.stream(values)
                .map(e -> new ConsoleMetaController.EnumItem(e.code(), e.label()))
                .toList();
    }
}
