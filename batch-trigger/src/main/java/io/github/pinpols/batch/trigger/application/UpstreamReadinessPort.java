package io.github.pinpols.batch.trigger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** 上游作业就绪状态查询端口。 */
public interface UpstreamReadinessPort {

  /** 上游作业在指定业务日是否就绪；返回结果生效时刻供依赖作业 SLA 使用。 */
  Optional<Instant> readyAt(String tenantId, String upstreamJobCode, LocalDate bizDate);
}
