package com.example.batch.orchestrator.infrastructure.router;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.application.route.WorkerRoutingPolicy;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 默认 Worker 路由选择策略。
 *
 * <p>从候选 {@link WorkerRouteModel} 列表中过滤出 {@code available=true} 的节点，
 * 按 {@code priority} 降序取最高优先级者作为路由结果。
 * 若所有候选均不可用，则回退返回列表第一个元素，保证调用方始终能拿到非空结果。
 */
@Component
public class DefaultWorkerRoutingPolicy implements WorkerRoutingPolicy {

  @Override
  public WorkerRouteModel select(List<WorkerRouteModel> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    return candidates.stream()
        .filter(candidate -> Boolean.TRUE.equals(candidate.getAvailable()))
        .max(
            Comparator.comparingInt(
                candidate -> candidate.getPriority() == null ? 0 : candidate.getPriority()))
        .orElse(candidates.get(0));
  }
}
