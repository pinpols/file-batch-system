package io.github.pinpols.batch.console.domain.job.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.pinpols.batch.console.support.web.ConsoleResponseFieldReader;
import java.util.List;
import java.util.Map;

/**
 * 手工触发 dry-run 校验结果。{@code errors} 仅在存在校验错误时出现（历史 map 在无错误时不写该键）， 故 {@code NON_NULL} 省略以保持 wire 一致。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleDryRunResultResponse(
    String tenantId, String jobCode, String bizDate, Boolean valid, List<String> errors) {

  @SuppressWarnings("unchecked")
  public static ConsoleDryRunResultResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    Object errors = ConsoleResponseFieldReader.value(row, "errors");
    return new ConsoleDryRunResultResponse(
        ConsoleResponseFieldReader.stringValue(row, "tenantId"),
        ConsoleResponseFieldReader.stringValue(row, "jobCode"),
        ConsoleResponseFieldReader.stringValue(row, "bizDate"),
        ConsoleResponseFieldReader.booleanValue(row, "valid"),
        errors == null ? null : (List<String>) errors);
  }
}
