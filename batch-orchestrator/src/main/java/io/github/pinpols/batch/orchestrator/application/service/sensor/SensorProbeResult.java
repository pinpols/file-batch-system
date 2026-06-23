package io.github.pinpols.batch.orchestrator.application.service.sensor;

import java.util.List;
import java.util.Map;

/**
 * ADR-028 sensor 单次探测的返回。
 *
 * <p>调用方约定：
 *
 * <ul>
 *   <li>MATCHED：{@code output} 写入 workflow_node_run.output JSONB，下游可走 ADR-009 DSL 引用
 *   <li>NOT_YET：{@code output} 通常为空；scheduler 等下个 poll_interval 再探
 *   <li>ERROR：填 {@code errorKey} / {@code errorArgs} 走 i18n；连续 ERROR 3 次推进 FAILED
 * </ul>
 */
public record SensorProbeResult(
    SensorProbeStatus status, Map<String, Object> output, String errorKey, List<String> errorArgs) {

  public static SensorProbeResult matched(Map<String, Object> output) {
    return new SensorProbeResult(SensorProbeStatus.MATCHED, output, null, List.of());
  }

  public static SensorProbeResult notYet() {
    return new SensorProbeResult(SensorProbeStatus.NOT_YET, Map.of(), null, List.of());
  }

  public static SensorProbeResult error(String errorKey, List<String> errorArgs) {
    return new SensorProbeResult(SensorProbeStatus.ERROR, Map.of(), errorKey, errorArgs);
  }
}
