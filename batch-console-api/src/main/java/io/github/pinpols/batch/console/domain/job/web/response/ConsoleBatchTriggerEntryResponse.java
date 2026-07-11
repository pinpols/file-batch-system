package io.github.pinpols.batch.console.domain.job.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 批量触发单条结果。三条分支各带不同字段（dryRun / 成功 / 失败），故用 {@code NON_NULL} 省略缺席键， 与历史逐分支 {@code Map.put} 的 wire
 * 一致：
 *
 * <ul>
 *   <li>dryRun：{@code dryRun=true} + {@code status} + {@code result}
 *   <li>成功：{@code status=SUCCESS} + {@code instanceNo}
 *   <li>失败：{@code status=FAILED} + {@code error}
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleBatchTriggerEntryResponse(
    Integer index,
    String jobCode,
    String bizDate,
    Boolean dryRun,
    String status,
    ConsoleDryRunResultResponse result,
    String instanceNo,
    String error) {}
