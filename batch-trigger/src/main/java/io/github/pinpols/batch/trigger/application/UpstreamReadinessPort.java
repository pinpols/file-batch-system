package io.github.pinpols.batch.trigger.application;

import java.time.LocalDate;

/** 上游作业就绪状态查询端口。 */
public interface UpstreamReadinessPort {

  /** 上游作业在指定业务日是否已经满足触发条件。 */
  boolean isReady(String tenantId, String upstreamJobCode, LocalDate bizDate);
}
