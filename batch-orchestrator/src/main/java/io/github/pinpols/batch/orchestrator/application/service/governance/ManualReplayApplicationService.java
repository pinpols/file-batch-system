package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 手动重放使用稳定去重键，并在重排成功后生成操作号。 */
@Service
@RequiredArgsConstructor
public class ManualReplayApplicationService {

  private final RetryGovernanceService retryGovernanceService;

  public String replayTask(String tenantId, Long taskId) {
    String eventKey = OutboxEventKeyGenerator.forManualReplay(tenantId, "task", taskId);
    retryGovernanceService.retryTask(tenantId, taskId, eventKey);
    return IdGenerator.newBusinessNo("rpl");
  }

  public String replayPartition(String tenantId, Long partitionId) {
    String eventKey = OutboxEventKeyGenerator.forManualReplay(tenantId, "partition", partitionId);
    retryGovernanceService.retryPartition(tenantId, partitionId, eventKey);
    return IdGenerator.newBusinessNo("rpl");
  }
}
