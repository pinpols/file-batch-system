package io.github.pinpols.batch.console.domain.job.application.contract.request;

import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.validation.ValidResourceCode;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.Data;
import org.springframework.scheduling.support.CronExpression;

@Data
public class JobDefinitionCreateRequest {
  private static final Set<String> SUPPORTED_JOB_TYPES = DictEnum.codes(JobType.class);

  @ValidTenantId
  private String tenantId;

  @ValidResourceCode
  private String jobCode;

  @Size(max = 64)
  @Pattern(
      regexp = "^$|^[a-zA-Z][a-zA-Z0-9_-]{0,63}$",
      message = "dependsOnJobCode must start with a letter and contain only letters, digits,"
          + " underscore or hyphen")
  private String dependsOnJobCode;

  @Size(max = 256)
  private String jobName;

  // 具体取值由 isJobTypeSupported() 直接跟随 JobType，避免复制正则后新增 worker 时漏同步。
  @NotBlank
  private String jobType;

  private String bizType;

  // 与 ScheduleType enum (CRON/FIXED_RATE/MANUAL) + DB CHECK 对齐
  @NotBlank
  @Pattern(
      regexp = "^(CRON|FIXED_RATE|MANUAL)$",
      message = "scheduleType must be one of: CRON/FIXED_RATE/MANUAL")
  private String scheduleType;

  private String scheduleExpr;
  private String timezone;
  private String triggerMode;
  private String workerGroup;
  private String queueCode;
  private String calendarCode;
  private String windowCode;
  private Boolean dagEnabled;
  private String shardStrategy;

  /**
   * 执行模式 ExecutionMode 枚举 code:FULL / INCREMENTAL / CDC,缺省 FULL。
   *
   * <p>INCREMENTAL 必须配合 watermarkField 才生效;CDC 当前是占位,worker 暂不实现。
   */
  @Size(max = 16)
  private String executionMode;

  /** 增量模式下的水位字段名(例:update_time / id);FULL 模式下应为空。 */
  @Size(max = 64)
  private String watermarkField;

  private String retryPolicy;

  @Min(value = 0, message = "retryMaxCount must be >= 0")
  private Integer retryMaxCount;

  @Min(value = 0, message = "timeoutSeconds must be >= 0")
  private Integer timeoutSeconds;

  private String executionHandler;
  private String paramSchema;
  private String defaultParams;

  @Min(value = 1, message = "priority must be between 1 and 9")
  @Max(value = 9, message = "priority must be between 1 and 9")
  private Integer priority;

  private Boolean enabled;
  private String description;

  /** scheduleType=CRON 时 scheduleExpr 必须是合法 cron(Spring 6 段;5 段前补秒位),防止非法表达式入库后在运行期失败。 */
  @AssertTrue(message = "scheduleExpr must be a valid cron expression when scheduleType=CRON")
  public boolean isScheduleExprValidForCron() {
    if (!"CRON".equals(scheduleType) || scheduleExpr == null || scheduleExpr.isBlank()) {
      return true;
    }
    try {
      String value = scheduleExpr.trim();
      CronExpression.parse(value.split("\\s+").length == 5 ? "0 " + value : value);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** executionMode=INCREMENTAL 时 watermarkField 必填,否则增量作业无水位跑不起来。 */
  @AssertTrue(message = "watermarkField is required when executionMode=INCREMENTAL")
  public boolean isWatermarkPresentForIncremental() {
    return !"INCREMENTAL".equals(executionMode)
        || (watermarkField != null && !watermarkField.isBlank());
  }

  /** jobType 必须与公共枚举及数据库 CHECK 使用同一词表。空值交由 {@link NotBlank} 报错。 */
  @AssertTrue(message = "jobType must be a supported JobType")
  public boolean isJobTypeSupported() {
    return EmptyChecks.isBlank(jobType) || SUPPORTED_JOB_TYPES.contains(jobType);
  }
}
