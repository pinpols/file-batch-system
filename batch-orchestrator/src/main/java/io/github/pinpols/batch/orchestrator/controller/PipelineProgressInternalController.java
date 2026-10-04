package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.infrastructure.progress.PipelineStageProgressCache;
import io.github.pinpols.batch.orchestrator.infrastructure.progress.PipelineStageProgressCache.PipelineSnapshot;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pipeline stage 行级进度内部查询端点(orchestrator → console-api)。
 *
 * <p>仅服务于 {@code GET /console/queries/pipeline-progress};console-api 不能直读 orchestrator 内部 cache, 走
 * internal 端点统一鉴权 + 协议演进控制。
 *
 * <p>详见 {@code docs/design/pipeline-stage-progress-display.md}。
 */
@RestController
@RequestMapping("/internal/pipeline-progress")
@RequiredArgsConstructor
public class PipelineProgressInternalController {

  private final PipelineStageProgressCache cache;

  /** 按 pipeline 实例查询，并聚合同一 stage 的并发分片。 */
  @GetMapping("/by-pipeline")
  public List<PipelineProgressItem> queryByPipeline(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("pipelineInstanceId") Long pipelineInstanceId) {
    return cache.snapshotByPipeline(tenantId, pipelineInstanceId).stream()
        .map(PipelineProgressInternalController::toPipelineProgressItem)
        .toList();
  }

  private static PipelineProgressItem toPipelineProgressItem(PipelineSnapshot snapshot) {
    return new PipelineProgressItem(
        snapshot.stageCode(),
        snapshot.rowsProcessed(),
        snapshot.totalRowsHint(),
        snapshot.heartbeatAt());
  }

  public record PipelineProgressItem(
      String stageCode, Long rowsProcessed, Long totalRowsHint, Instant heartbeatAt) {}
}
