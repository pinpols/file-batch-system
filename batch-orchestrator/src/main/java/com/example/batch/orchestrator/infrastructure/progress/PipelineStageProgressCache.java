package com.example.batch.orchestrator.infrastructure.progress;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Pipeline stage 行级进度 in-memory cache(orchestrator 节点本地)。
 *
 * <p>2026-06-03 落地 {@code docs/design/pipeline-stage-progress-display.md}:
 *
 * <ul>
 *   <li><b>不持久化</b>:进度只关心"当前"态,过期无意义;worker 崩溃后续跑由 {@code
 *       pipeline_progress.position_marker}(ADR-038)回退,不靠本 cache
 *   <li><b>TTL 5 分钟</b>:心跳默认 30s,5min 容 10 个心跳间隔,够防"心跳没了但 cache 还在显进度" 误导;过期由读侧 lazy 清理
 *   <li><b>节点本地</b>:multi-orchestrator 部署时,FE 只能查到收过该 worker 心跳的那个节点; 这种"不一致"窗口接受(进度本就有延迟,信号性 >
 *       准确性)。若需要跨节点一致,后续上 Redis
 *   <li><b>key = (tenantId, workerCode)</b>:wire 里不带 pipelineInstanceId(避免 SDK 协议侵入 业务概念);一个 worker
 *       同时只跑一个 CLAIM,workerCode 就够定位
 * </ul>
 */
@Component
public class PipelineStageProgressCache {

  private static final Duration TTL = Duration.ofMinutes(5);

  private final Map<Key, Snapshot> store = new ConcurrentHashMap<>();

  /** 心跳路径调用,更新 cache(null 字段不更新)。 */
  public void publish(String tenantId, String workerCode, Long rowsProcessed, Long totalRowsHint) {
    if (tenantId == null || workerCode == null) {
      return;
    }
    if (rowsProcessed == null && totalRowsHint == null) {
      // 两个都 null = stage 结束或不上报进度,清掉旧值避免 stale
      store.remove(new Key(tenantId, workerCode));
      return;
    }
    store.put(
        new Key(tenantId, workerCode), new Snapshot(rowsProcessed, totalRowsHint, Instant.now()));
  }

  /** Console 端点读:批量取多个 worker 的最新进度。返回 map 仅含**有进度**的 worker; 过期(> TTL) lazy 清理后也不返回。 */
  public Map<String, Snapshot> snapshot(String tenantId, java.util.Collection<String> workerCodes) {
    if (tenantId == null || workerCodes == null || workerCodes.isEmpty()) {
      return Map.of();
    }
    Instant cutoff = Instant.now().minus(TTL);
    return workerCodes.stream()
        .map(wc -> new Key(tenantId, wc))
        .map(
            k -> {
              Snapshot s = store.get(k);
              if (s == null) {
                return null;
              }
              if (s.heartbeatAt().isBefore(cutoff)) {
                store.remove(k, s);
                return null;
              }
              return Map.entry(k.workerCode(), s);
            })
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /** 仅供单测:清空全部。 */
  public void clearAllForTesting() {
    store.clear();
  }

  private record Key(String tenantId, String workerCode) {}

  public record Snapshot(Long rowsProcessed, Long totalRowsHint, Instant heartbeatAt) {}
}
