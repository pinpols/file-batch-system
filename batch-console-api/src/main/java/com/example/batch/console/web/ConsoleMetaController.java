package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/meta")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
public class ConsoleMetaController {

    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/enums")
    public CommonResponse<Map<String, List<EnumItem>>> enums() {
        Map<String, List<EnumItem>> result = new LinkedHashMap<>();
        result.put("triggerType", enumItems(com.example.batch.common.enums.TriggerType.values()));
        result.put("jobType", List.of(
                new EnumItem("GENERAL", "通用作业"), new EnumItem("IMPORT", "导入"),
                new EnumItem("EXPORT", "导出"), new EnumItem("DISPATCH", "派发"),
                new EnumItem("WORKFLOW", "工作流")));
        result.put("scheduleType", List.of(
                new EnumItem("CRON", "Cron 表达式"), new EnumItem("FIXED_RATE", "固定频率"),
                new EnumItem("MANUAL", "手动"), new EnumItem("EVENT", "事件触发"),
                new EnumItem("ONE_TIME", "一次性")));
        result.put("triggerMode", List.of(
                new EnumItem("SCHEDULED", "定时"), new EnumItem("API", "API"),
                new EnumItem("MANUAL", "手动"), new EnumItem("EVENT", "事件"),
                new EnumItem("MIXED", "混合")));
        result.put("shardStrategy", List.of(
                new EnumItem("NONE", "不分片"), new EnumItem("STATIC", "静态分片"),
                new EnumItem("DYNAMIC", "动态分片"), new EnumItem("AUTO", "自动")));
        result.put("retryPolicy", List.of(
                new EnumItem("NONE", "不重试"), new EnumItem("FIXED", "固定间隔"),
                new EnumItem("EXPONENTIAL", "指数退避")));
        result.put("instanceStatus", List.of(
                new EnumItem("CREATED", "已创建"), new EnumItem("WAITING", "等待中"),
                new EnumItem("READY", "就绪"), new EnumItem("RUNNING", "运行中"),
                new EnumItem("SUCCESS", "成功"), new EnumItem("PARTIAL_FAILED", "部分失败"),
                new EnumItem("FAILED", "失败"), new EnumItem("CANCELLED", "已取消"),
                new EnumItem("TERMINATED", "已终止")));
        result.put("workflowNodeType", List.of(
                new EnumItem("START", "开始"), new EnumItem("END", "结束"),
                new EnumItem("JOB", "作业"), new EnumItem("PIPELINE", "流水线"),
                new EnumItem("CONDITION", "条件"), new EnumItem("FORK", "分支"),
                new EnumItem("JOIN", "汇聚")));
        result.put("channelType", List.of(
                new EnumItem("SFTP", "SFTP"), new EnumItem("S3", "S3"),
                new EnumItem("HTTP", "HTTP"), new EnumItem("LOCAL", "本地"),
                new EnumItem("API_PUSH", "API推送")));
        return responseFactory.success(result);
    }

    @GetMapping("/queues")
    public CommonResponse<List<SimpleOption>> queues(@RequestParam("tenantId") String tenantId) {
        return responseFactory.success(querySimpleOptions(tenantId, "select queue_code as code, queue_code as label from batch.resource_queue where tenant_id = ? and enabled = true order by queue_code"));
    }

    @GetMapping("/calendars")
    public CommonResponse<List<SimpleOption>> calendars(@RequestParam("tenantId") String tenantId) {
        return responseFactory.success(querySimpleOptions(tenantId, "select calendar_code as code, calendar_code as label from batch.business_calendar where tenant_id = ? and enabled = true order by calendar_code"));
    }

    @GetMapping("/windows")
    public CommonResponse<List<SimpleOption>> windows(@RequestParam("tenantId") String tenantId) {
        return responseFactory.success(querySimpleOptions(tenantId, "select window_code as code, window_code as label from batch.batch_window where tenant_id = ? and enabled = true order by window_code"));
    }

    @GetMapping("/worker-groups")
    public CommonResponse<List<SimpleOption>> workerGroups(@RequestParam("tenantId") String tenantId) {
        return responseFactory.success(querySimpleOptions(tenantId, "select distinct worker_group as code, worker_group as label from batch.worker_registry where tenant_id = ? and status = 'ONLINE' order by worker_group"));
    }

    private final javax.sql.DataSource dataSource;

    private List<SimpleOption> querySimpleOptions(String tenantId, String sql) {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        return jdbc.query(sql, (rs, i) -> new SimpleOption(rs.getString("code"), rs.getString("label")), tenantId);
    }

    private static List<EnumItem> enumItems(com.example.batch.common.enums.TriggerType[] values) {
        return Arrays.stream(values).map(e -> new EnumItem(e.code(), e.label())).toList();
    }

    public record EnumItem(String code, String label) {}
    public record SimpleOption(String code, String label) {}
}
