package io.github.pinpols.batch.console.domain.file.view;

/** 文件列表统计查询的固定列投影。 */
public record FileRecordSummaryView(Long arrivedToday, Long pending, Long processed, Long failed) {}
