package io.github.pinpols.batch.console.domain.job.query;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.utils.Texts;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.Builder;

@Builder
public record JobInstanceQuery(
    String tenantId,
    String jobCode,
    String exactJobCode,
    String instanceStatus,
    /** 多状态过滤;非空时 IN (...) 查询,优先于 instanceStatus 单值。 */
    List<String> instanceStatuses,
    String instanceNo,
    String bizDate,
    String traceId,
    Instant startedFrom,
    Instant startedTo,
    String sortBy,
    Integer minDurationSeconds,
    Boolean slaBreached,
    PageRequest pageRequest,
    /**
     * 双轨分页 cursor 模式(ADR-031):非 null 时 Mapper 走 cursor 谓词(id &lt; #{cursorId} 或类似),不读
     * pageRequest.pageNo。 形态:CursorCodec.decode 出来的 {@code id} long 值;null 表示首页或 offset 模式。
     */
    Long cursorId) {

  /**
   * 由 {@code bizDate} 严格按 {@code yyyy-MM-dd} 派生的业务日。Mapper 据此直接比较 {@code biz_date} 列以走索引和月分区裁剪；
   * 非规范或非法输入返回 null，SQL 返回空结果，与原先按字符串精确匹配的语义一致。
   */
  public LocalDate bizDateFilter() {
    return parseCanonicalBizDate(bizDate);
  }

  private static LocalDate parseCanonicalBizDate(String bizDate) {
    if (!Texts.hasText(bizDate)) {
      return null;
    }
    try {
      return LocalDate.parse(bizDate, DateTimeFormatter.ISO_LOCAL_DATE);
    } catch (DateTimeParseException unparsable) {
      return null;
    }
  }
}
