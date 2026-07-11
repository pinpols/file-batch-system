package io.github.pinpols.batch.console.domain.job.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * 实例生命周期动作（cancel / terminate / pause / resume）结果。
 *
 * <p>{@code cancelRequestedTasks} 仅在对 RUNNING 实例发起 cancel 时出现（记录被置为取消请求的在途任务数）， 其余动作不含该字段，故 {@code
 * NON_NULL} 省略以保持 wire 一致。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleInstanceActionResponse(
    Long id, String instanceNo, String status, Integer cancelRequestedTasks) {

  public static ConsoleInstanceActionResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleInstanceActionResponse(
        JobResponseFieldReader.longValue(row, "id"),
        JobResponseFieldReader.stringValue(row, "instanceNo"),
        JobResponseFieldReader.stringValue(row, "status"),
        JobResponseFieldReader.integerValue(row, "cancelRequestedTasks"));
  }
}
