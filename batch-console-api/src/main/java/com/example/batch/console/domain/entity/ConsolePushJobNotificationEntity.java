package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

/**
 * 任务终态推送去重记录。
 *
 * <p>对应表 {@code batch.console_push_job_notification}(V148)。 ConsolePushJobNotifier 周期写入,确保同一 (tenant,
 * instance) 仅推送一次。
 */
@Data
public class ConsolePushJobNotificationEntity {

  private Long id;
  private String tenantId;
  private Long jobInstanceId;
  private Instant notifiedAt;
}
