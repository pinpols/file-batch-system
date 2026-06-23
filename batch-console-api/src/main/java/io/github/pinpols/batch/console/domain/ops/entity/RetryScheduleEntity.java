package io.github.pinpols.batch.console.domain.ops.entity;

import io.github.pinpols.batch.common.i18n.LocalizedErrorCarrier;
import java.time.Instant;
import lombok.Data;

@Data
public class RetryScheduleEntity implements LocalizedErrorCarrier {

  private Long id;
  private String tenantId;
  private String relatedType;
  private Long relatedId;
  private String retryPolicy;
  private Integer retryCount;
  private Integer maxRetryCount;
  private Instant nextRetryAt;
  private String retryStatus;
  private String dedupKey;
  private String lastErrorCode;
  private String lastErrorMessage;

  /** i18n message key,V78+ 写入;读路径按当前 Locale 渲染时优先于 lastErrorMessage。 */
  private String lastErrorKey;

  /** i18n 占位符参数 JSON 数组。 */
  private String lastErrorArgs;

  private Instant createdAt;
  private Instant updatedAt;

  // ─── LocalizedErrorCarrier 桥接(字段命名变体:lastError* 而非 error*) ─────────────────
  @Override
  public String getErrorMessage() {
    return getLastErrorMessage();
  }

  @Override
  public String getErrorKey() {
    return getLastErrorKey();
  }

  @Override
  public String getErrorArgs() {
    return getLastErrorArgs();
  }
}
