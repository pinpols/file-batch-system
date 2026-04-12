package com.example.batch.console.service;

import com.example.batch.common.enums.TriggerType;
import com.example.batch.console.repository.ConsoleMetaQueryRepository;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.response.ConsoleMetaEnumItem;
import com.example.batch.console.web.response.ConsoleMetaOption;

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

    public Map<String, List<ConsoleMetaEnumItem>> enums() {
        Map<String, List<ConsoleMetaEnumItem>> result = new LinkedHashMap<>();
        result.put("triggerType", enumItems(TriggerType.values()));
        result.put(
                "jobType",
                List.of(
                        new ConsoleMetaEnumItem("GENERAL", "通用作业"),
                        new ConsoleMetaEnumItem("IMPORT", "导入"),
                        new ConsoleMetaEnumItem("EXPORT", "导出"),
                        new ConsoleMetaEnumItem("DISPATCH", "派发"),
                        new ConsoleMetaEnumItem("WORKFLOW", "工作流")));
        result.put(
                "scheduleType",
                List.of(
                        new ConsoleMetaEnumItem("CRON", "Cron 表达式"),
                        new ConsoleMetaEnumItem("FIXED_RATE", "固定频率"),
                        new ConsoleMetaEnumItem("MANUAL", "手动"),
                        new ConsoleMetaEnumItem("EVENT", "事件触发"),
                        new ConsoleMetaEnumItem("ONE_TIME", "一次性")));
        result.put(
                "triggerMode",
                List.of(
                        new ConsoleMetaEnumItem("SCHEDULED", "定时"),
                        new ConsoleMetaEnumItem("API", "API"),
                        new ConsoleMetaEnumItem("MANUAL", "手动"),
                        new ConsoleMetaEnumItem("EVENT", "事件"),
                        new ConsoleMetaEnumItem("MIXED", "混合")));
        result.put(
                "shardStrategy",
                List.of(
                        new ConsoleMetaEnumItem("NONE", "不分片"),
                        new ConsoleMetaEnumItem("STATIC", "静态分片"),
                        new ConsoleMetaEnumItem("DYNAMIC", "动态分片"),
                        new ConsoleMetaEnumItem("AUTO", "自动")));
        result.put(
                "retryPolicy",
                List.of(
                        new ConsoleMetaEnumItem("NONE", "不重试"),
                        new ConsoleMetaEnumItem("FIXED", "固定间隔"),
                        new ConsoleMetaEnumItem("EXPONENTIAL", "指数退避")));
        result.put(
                "instanceStatus",
                List.of(
                        new ConsoleMetaEnumItem("CREATED", "已创建"),
                        new ConsoleMetaEnumItem("WAITING", "等待中"),
                        new ConsoleMetaEnumItem("READY", "就绪"),
                        new ConsoleMetaEnumItem("RUNNING", "运行中"),
                        new ConsoleMetaEnumItem("SUCCESS", "成功"),
                        new ConsoleMetaEnumItem("PARTIAL_FAILED", "部分失败"),
                        new ConsoleMetaEnumItem("FAILED", "失败"),
                        new ConsoleMetaEnumItem("CANCELLED", "已取消"),
                        new ConsoleMetaEnumItem("TERMINATED", "已终止")));
        result.put(
                "workflowNodeType",
                List.of(
                        new ConsoleMetaEnumItem("START", "开始"),
                        new ConsoleMetaEnumItem("END", "结束"),
                        new ConsoleMetaEnumItem("JOB", "作业"),
                        new ConsoleMetaEnumItem("PIPELINE", "流水线"),
                        new ConsoleMetaEnumItem("CONDITION", "条件"),
                        new ConsoleMetaEnumItem("FORK", "分支"),
                        new ConsoleMetaEnumItem("JOIN", "汇聚")));
        result.put(
                "channelType",
                List.of(
                        new ConsoleMetaEnumItem("SFTP", "SFTP"),
                        new ConsoleMetaEnumItem("S3", "S3"),
                        new ConsoleMetaEnumItem("HTTP", "HTTP"),
                        new ConsoleMetaEnumItem("LOCAL", "本地"),
                        new ConsoleMetaEnumItem("API_PUSH", "API推送")));
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

    private List<ConsoleMetaOption> toOptions(List<ConsoleMetaQueryRepository.SimpleOptionView> rows) {
        return rows.stream()
                .map(row -> new ConsoleMetaOption(row.getCode(), row.getLabel()))
                .toList();
    }

    private static List<ConsoleMetaEnumItem> enumItems(TriggerType[] values) {
        return Arrays.stream(values)
                .map(e -> new ConsoleMetaEnumItem(e.code(), e.label()))
                .toList();
    }
}
