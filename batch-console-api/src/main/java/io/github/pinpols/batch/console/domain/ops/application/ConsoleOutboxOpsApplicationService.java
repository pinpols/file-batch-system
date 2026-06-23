package io.github.pinpols.batch.console.domain.ops.application;

import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxCleanupResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxRepublishResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxStatsResponse;
import java.util.List;

/** Outbox 运维操作：清理过期事件、手动重投、积压统计。 */
public interface ConsoleOutboxOpsApplicationService {

  ConsoleOutboxStatsResponse stats(String tenantId);

  ConsoleOutboxCleanupResponse cleanup(String tenantId, int retainDays);

  ConsoleOutboxRepublishResponse republish(String tenantId, List<Long> ids);
}
