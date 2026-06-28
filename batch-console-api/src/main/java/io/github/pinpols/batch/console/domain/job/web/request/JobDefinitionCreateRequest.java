package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidResourceCode;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class JobDefinitionCreateRequest {
  @ValidTenantId private String tenantId;

  @ValidResourceCode private String jobCode;

  @Size(max = 64)
  @Pattern(
      regexp = "^$|^[a-zA-Z][a-zA-Z0-9_-]{0,63}$",
      message =
          "dependsOnJobCode must start with a letter and contain only letters, digits,"
              + " underscore or hyphen")
  private String dependsOnJobCode;

  @Size(max = 256)
  private String jobName;

  // 与 batch_common JobType enum + DB ck_job_definition_job_type CHECK 对齐，
  // 之前没限制取值，console 接受任意字符串就 INSERT 撞 CHECK 返 500 数据完整性异常。
  @NotBlank
  @Pattern(
      regexp = "^(GENERAL|IMPORT|EXPORT|PROCESS|DISPATCH|WORKFLOW)$",
      message = "jobType must be one of: GENERAL/IMPORT/EXPORT/PROCESS/DISPATCH/WORKFLOW")
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
  private Integer retryMaxCount;
  private Integer timeoutSeconds;
  private String executionHandler;
  private String paramSchema;
  private String defaultParams;
  private Integer priority;
  private Boolean enabled;
  private String description;
}
