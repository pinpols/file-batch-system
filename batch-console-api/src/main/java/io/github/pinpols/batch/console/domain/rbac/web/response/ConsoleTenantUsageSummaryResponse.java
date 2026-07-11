package io.github.pinpols.batch.console.domain.rbac.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 租户配额用量摘要响应（{@code GET /api/console/tenant-self-service/usage}）。
 *
 * <p>历史实现按系统参数逐项 {@code ifPresent} 写入 {@code runningJobs / dailyTriggers / fileCount}，
 * 参数缺失时对应键不出现。故用 {@code NON_NULL} 省略 null 字段，保持键集与历史 wire 一致。 值均为系统参数原始字符串。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleTenantUsageSummaryResponse(
    String runningJobs, String dailyTriggers, String fileCount) {}
