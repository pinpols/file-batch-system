package com.example.batch.worker.imports.domain;

import com.example.batch.common.time.BatchDateTimeSupport;
import java.time.Instant;

/** 导入过程中未通过校验或处理失败的问题行记录。 保存行号、所在阶段、错误码及原始数据， 支持标记为"已跳过"并记录跳过动作，用于生成导入错误报告。 */
public record ImportBadRecord(
    Long recordNo,
    String stageCode,
    String errorCode,
    String errorMessage,
    Object rawRecord,
    boolean skipped,
    String skipAction,
    Instant createdAt) {
  public ImportBadRecord {
    if (createdAt == null) {
      createdAt = BatchDateTimeSupport.utcNow();
    }
  }
}
