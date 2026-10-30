package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import io.github.pinpols.batch.orchestrator.domain.query.WorkflowMonitoringQuery;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowMonitoringMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用同一只读事务设置数据库租户上下文并查询候选，连接归还后不保留租户状态。 */
@Service
@RequiredArgsConstructor
public class WorkflowMonitoringCandidateService {
  private final WorkflowMonitoringMapper mapper;

  @Transactional(readOnly = true)
  public List<WorkflowMonitoringAlertCandidate> select(WorkflowMonitoringQuery query) {
    mapper.setTenantContext(query.tenantId());
    return mapper.selectCandidates(query);
  }
}
