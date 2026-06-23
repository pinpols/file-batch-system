package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.CompensationCommandEntity;
import java.util.Map;

/**
 * 单一补偿类型的策略接口，每个实现处理一种 {@code compensationType}（JOB / STEP / PARTITION / FILE / BATCH / DLQ）。
 * {@link DefaultCompensationService} 通过构造期建立的 Map 路由至对应处理器，替代原始的 6 路 switch。
 */
@FunctionalInterface
public interface CompensationHandler {

  Map<String, Object> handle(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity);
}
