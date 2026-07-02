package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidResourceCode;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.time.ZoneId;
import lombok.Data;

@Data
public class BatchWindowCreateRequest {
  @ValidTenantId private String tenantId;

  @ValidResourceCode private String windowCode;

  @Size(max = 256)
  private String windowName;

  @NotBlank
  @Size(max = 64)
  private String timezone;

  @NotBlank private String startTime;
  @NotBlank private String endTime;

  @Size(max = 32)
  private String endStrategy;

  @Size(max = 32)
  private String outOfWindowAction;

  private Boolean allowCrossDay;
  private Boolean enabled;

  @Size(max = 512)
  private String description;

  /** timezone 必须是合法 IANA 时区（如 Asia/Shanghai），拒绝 Mars/Phobos 之类。 */
  @AssertTrue(message = "timezone is invalid")
  public boolean isTimezoneValid() {
    if (timezone == null || timezone.isBlank()) {
      return true; // 空由 @NotBlank 处理
    }
    try {
      ZoneId.of(timezone);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** 非跨天窗口 startTime 必须早于 endTime，拒绝 20:00-08:00 或 start==end 的退化区间。 */
  @AssertTrue(message = "startTime must be earlier than endTime when allowCrossDay is false")
  public boolean isWindowRangeValid() {
    if (Boolean.TRUE.equals(allowCrossDay) || startTime == null || endTime == null) {
      return true;
    }
    try {
      return LocalTime.parse(startTime).isBefore(LocalTime.parse(endTime));
    } catch (RuntimeException e) {
      return true; // 时间格式错误交由其它校验处理，这里只管区间逻辑
    }
  }
}
