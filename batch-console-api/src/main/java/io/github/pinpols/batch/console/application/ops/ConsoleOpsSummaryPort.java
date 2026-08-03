package io.github.pinpols.batch.console.application.ops;

import io.github.pinpols.batch.console.shared.view.ConsoleOpsSummaryResponse;

/**
 * 运维摘要查询端口。
 *
 * <p>只暴露跨上下文需要的只读摘要，不把 Ops 的应用服务实现泄漏给观测领域。
 */
public interface ConsoleOpsSummaryPort {

  /** 返回指定租户的运维摘要。 */
  ConsoleOpsSummaryResponse summary(String tenantId);
}
