package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeartbeatDirectiveTest {

  @Test
  void emptyOrNullResponseFallsBackToNormal() {
    // 老平台心跳回包为空(bodiless)→ NORMAL,不暂停
    for (Map<String, Object> resp : List.of(Map.<String, Object>of())) {
      HeartbeatDirective d = HeartbeatDirective.fromResponse(resp);
      assertThat(d.platformStatus()).isEqualTo("NORMAL");
      assertThat(d.shouldDrain()).isFalse();
      assertThat(d.toRuntimeState()).isEqualTo(WorkerRuntimeState.NORMAL);
    }
    assertThat(HeartbeatDirective.fromResponse(null).toRuntimeState())
        .isEqualTo(WorkerRuntimeState.NORMAL);
  }

  @Test
  void parsesNormalDirective() {
    Map<String, Object> resp = new HashMap<>();
    resp.put("platformStatus", "NORMAL");
    resp.put("desiredMaxConcurrent", 8);
    resp.put("shouldDrain", false);
    resp.put("pausedTaskTypes", List.of());
    resp.put("nextHeartbeatHint", null);

    HeartbeatDirective d = HeartbeatDirective.fromResponse(resp);

    assertThat(d.desiredMaxConcurrent()).isEqualTo(8);
    assertThat(d.shouldDrain()).isFalse();
    assertThat(d.pausedTaskTypes()).isEmpty();
    assertThat(d.nextHeartbeatHint()).isNull();
    assertThat(d.toRuntimeState()).isEqualTo(WorkerRuntimeState.NORMAL);
  }

  @Test
  void shouldDrainMapsToDraining() {
    Map<String, Object> resp = new HashMap<>();
    resp.put("platformStatus", "DRAINING");
    resp.put("shouldDrain", true);
    HeartbeatDirective d = HeartbeatDirective.fromResponse(resp);
    assertThat(d.toRuntimeState()).isEqualTo(WorkerRuntimeState.DRAINING);
    assertThat(d.toRuntimeState().acceptsNewTasks()).isFalse();
  }

  @Test
  void pausedStatusMapsToPaused() {
    // 平台预留态:即便 shouldDrain=false,PAUSED 也停止认领
    HeartbeatDirective d = HeartbeatDirective.fromResponse(Map.of("platformStatus", "PAUSED"));
    assertThat(d.toRuntimeState()).isEqualTo(WorkerRuntimeState.PAUSED);
    assertThat(d.toRuntimeState().acceptsNewTasks()).isFalse();
  }

  @Test
  void degradedStatusStillAcceptsTasks() {
    HeartbeatDirective d = HeartbeatDirective.fromResponse(Map.of("platformStatus", "DEGRADED"));
    assertThat(d.toRuntimeState()).isEqualTo(WorkerRuntimeState.DEGRADED);
    assertThat(d.toRuntimeState().acceptsNewTasks()).isTrue();
  }

  @Test
  void unknownStatusFallsBackToNormal() {
    // 向后兼容:平台后续加新态,老 SDK 不认 → 当 NORMAL 处理(不误暂停)
    HeartbeatDirective d =
        HeartbeatDirective.fromResponse(Map.of("platformStatus", "FUTURE_STATE"));
    assertThat(d.toRuntimeState()).isEqualTo(WorkerRuntimeState.NORMAL);
  }

  @Test
  void malformedFieldShapesDegradeGracefully() {
    // 平台回包字段类型不对(pausedTaskTypes 非 List、数值字段是非数字字符串)→ 不抛,降级:
    // status 仍读到(任意值都当字符串),非 List 的 paused → 空,非数字的 int 字段 → null
    Map<String, Object> resp = new HashMap<>();
    resp.put("platformStatus", "NORMAL");
    resp.put("pausedTaskTypes", "not-a-list"); // 错误形状
    resp.put("desiredMaxConcurrent", "eight"); // 非数字
    resp.put("nextHeartbeatHint", "soon"); // 非数字
    resp.put("shouldDrain", "yes"); // 非 Boolean.TRUE → 当 false

    HeartbeatDirective d = HeartbeatDirective.fromResponse(resp);

    assertThat(d.pausedTaskTypes()).isEmpty();
    assertThat(d.desiredMaxConcurrent()).isNull();
    assertThat(d.nextHeartbeatHint()).isNull();
    assertThat(d.shouldDrain()).isFalse();
    assertThat(d.toRuntimeState()).isEqualTo(WorkerRuntimeState.NORMAL);
  }

  @Test
  void drainTakesPrecedenceOverStatus() {
    // shouldDrain=true 即使 platformStatus 说别的也优先 DRAINING
    Map<String, Object> resp = new HashMap<>();
    resp.put("platformStatus", "NORMAL");
    resp.put("shouldDrain", true);
    assertThat(HeartbeatDirective.fromResponse(resp).toRuntimeState())
        .isEqualTo(WorkerRuntimeState.DRAINING);
  }
}
