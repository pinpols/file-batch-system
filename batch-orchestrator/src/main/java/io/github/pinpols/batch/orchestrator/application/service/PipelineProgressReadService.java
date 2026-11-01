package io.github.pinpols.batch.orchestrator.application.service;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.view.PipelineProgressClaimView;
import io.github.pinpols.batch.orchestrator.domain.view.PipelineProgressStageView;
import io.github.pinpols.batch.orchestrator.infrastructure.progress.PipelineStageProgressCache;
import io.github.pinpols.batch.orchestrator.mapper.PipelineProgressClaimMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 在同一租户事务快照中核对认领与阶段，禁止跨认领复用缓存。 */
@Service
@RequiredArgsConstructor
public class PipelineProgressReadService {
  private final PipelineProgressClaimMapper mapper;
  private final PipelineStageProgressCache cache;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<PipelineProgressItem> snapshot(String tenantId, Long pipelineInstanceId) {
    mapper.setTenantContext(tenantId);
    List<PipelineProgressClaimView> claims = mapper.selectLiveClaims(tenantId, pipelineInstanceId);
    if (EmptyChecks.isEmpty(claims)) {
      return List.of();
    }
    List<PipelineProgressStageView> stages =
        mapper.selectRunningStages(tenantId, pipelineInstanceId);
    return cache.snapshotByPipeline(tenantId, pipelineInstanceId, claims, stages).stream()
        .map(snapshot -> new PipelineProgressItem(
            snapshot.stageCode(),
            snapshot.rowsProcessed(),
            snapshot.totalRowsHint(),
            snapshot.heartbeatAt(),
            snapshot.stepRunIds()))
        .toList();
  }

  public record PipelineProgressItem(
      String stageCode,
      Long rowsProcessed,
      Long totalRowsHint,
      Instant heartbeatAt,
      List<Long> stepRunIds) {}
}
