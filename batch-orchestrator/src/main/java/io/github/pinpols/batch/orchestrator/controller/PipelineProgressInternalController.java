package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.infrastructure.progress.PipelineStageProgressCache;
import io.github.pinpols.batch.orchestrator.infrastructure.progress.PipelineStageProgressCache.Snapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

  /**
   * 批量查指定 workerCode 列表的最新进度。
   *
   * @param tenantId 租户(必填,multi-tenant 隔离)
   * @param workerCodes 逗号分隔的 workerCode 列表
   * @return 仅包含**有进度**的 worker 行(无进度 / 已过期的不返回)
   */
  @GetMapping
  public List<ProgressItem> query(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("workerCodes") List<String> workerCodes) {
    Map<String, Snapshot> snapshots = cache.snapshot(tenantId, workerCodes);
    return snapshots.entrySet().stream()
        .map(
            e ->
                new ProgressItem(
                    e.getKey(),
                    e.getValue().rowsProcessed(),
                    e.getValue().totalRowsHint(),
                    e.getValue().heartbeatAt()))
        .collect(Collectors.toUnmodifiableList());
  }

  public record ProgressItem(
      String workerCode, Long rowsProcessed, Long totalRowsHint, Instant heartbeatAt) {}
}
