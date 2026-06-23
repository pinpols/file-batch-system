package io.github.pinpols.batch.worker.imports.domain;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import java.time.Instant;

/**
 * 导入过程中未通过校验或处理失败的问题行记录。 保存行号、所在阶段、错误码及原始数据， 支持标记为"已跳过"并记录跳过动作，用于生成导入错误报告。
 *
 * <p>{@code sourceRowNum} / {@code sourceColumn} 为 Excel 物理定位:1-based 物理行号 + 出错列表头名，
 * 让用户能回原表(.xlsx)精确定位坏行;非 Excel 路径或无法定位到列时为空(向后兼容)。
 */
public record ImportBadRecordEntity(
    Long recordNo,
    String stageCode,
    String errorCode,
    String errorMessage,
    Object rawRecord,
    boolean skipped,
    String skipAction,
    Instant createdAt,
    Long sourceRowNum,
    String sourceColumn) {
  public ImportBadRecordEntity {
    if (createdAt == null) {
      createdAt = BatchDateTimeSupport.utcNow();
    }
  }
}
