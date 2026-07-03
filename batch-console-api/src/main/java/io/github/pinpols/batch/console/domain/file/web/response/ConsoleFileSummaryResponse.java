package io.github.pinpols.batch.console.domain.file.web.response;

/**
 * 文件列表页领域汇总卡数据。
 *
 * <p>口径均基于 batch.file_record 现有列(不新增字段、不编造):
 *
 * <ul>
 *   <li>{@code arrivedToday} — created_at 落在今日的文件数;
 *   <li>{@code pending} — file_status = RECEIVED(已登记待处理);
 *   <li>{@code processed} — file_status = LOADED(已处理);
 *   <li>{@code failed} — file_status = FAILED(失败)。
 * </ul>
 */
public record ConsoleFileSummaryResponse(
    long arrivedToday, long pending, long processed, long failed) {}
