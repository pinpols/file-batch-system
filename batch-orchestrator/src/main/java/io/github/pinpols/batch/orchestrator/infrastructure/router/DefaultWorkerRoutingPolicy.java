package io.github.pinpols.batch.orchestrator.infrastructure.router;

import io.github.pinpols.batch.common.model.WorkerRouteModel;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.orchestrator.application.route.WorkerRoutingPolicy;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 默认 Worker 路由选择策略。
 *
 * <p>从候选 {@link WorkerRouteModel} 列表中过滤出 {@code available=true} 的节点， 按 {@code priority}
 * 降序取最高优先级者作为路由结果。 若所有候选均不可用，则回退返回列表第一个元素，保证调用方始终能拿到非空结果。
 */
@Component
public class DefaultWorkerRoutingPolicy implements WorkerRoutingPolicy {

  @Override
  public WorkerRouteModel select(List<WorkerRouteModel> candidates) {
    if (EmptyChecks.isEmpty(candidates)) {
      return null;
    }
    WorkerRouteModel fallback =
        candidates.stream().filter(EmptyChecks::isNotNull).findFirst().orElse(null);
    if (EmptyChecks.isNull(fallback)) {
      return null;
    }
    return candidates.stream()
        .filter(EmptyChecks::isNotNull)
        .filter(candidate -> Boolean.TRUE.equals(candidate.getAvailable()))
        .max(Comparator.comparingInt(candidate -> Nullables.coalesce(candidate.getPriority(), 0)))
        .orElseGet(() -> fallback);
  }
}
