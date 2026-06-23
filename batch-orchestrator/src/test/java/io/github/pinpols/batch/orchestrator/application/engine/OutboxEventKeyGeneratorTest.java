package io.github.pinpols.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 2026-05-01 hardening 守护:验证各 scope 生成的 eventKey 互不冲突 + 同输入幂等。
 *
 * <p>Outbox dedup 完全靠 {@code uk_outbox_event_key},如果两个 scope 撞 key 会 silent 丢失事件,这里集中防回归。
 */
class OutboxEventKeyGeneratorTest {

  // ── 同输入 → 同 key(幂等)──────────────────────────────────────────────

  @Test
  void shouldProduceSameKeyForSameInput() {
    String k1 = OutboxEventKeyGenerator.forDispatch("t1", 100L);
    String k2 = OutboxEventKeyGenerator.forDispatch("t1", 100L);
    assertThat(k1).isEqualTo(k2);
  }

  @Test
  void shouldEncodeTenantIdAndScopePrefix() {
    assertThat(OutboxEventKeyGenerator.forDispatch("t1", 100L)).isEqualTo("t1:dispatch:100");
    assertThat(OutboxEventKeyGenerator.forFileRedispatch("t1", 100L))
        .isEqualTo("t1:file-redispatch:100");
    assertThat(OutboxEventKeyGenerator.forRetry("t1", 100L, 3)).isEqualTo("t1:retry:100:3");
    assertThat(OutboxEventKeyGenerator.forReclaim("t1", 100L, "worker-x"))
        .isEqualTo("t1:reclaim:100:worker-x");
    assertThat(OutboxEventKeyGenerator.forCompensation("t1", "cmp-001", 100L))
        .isEqualTo("t1:cmp:cmp-001:100");
    assertThat(OutboxEventKeyGenerator.forWorkflowNodeDispatch("t1", 50L, "JOB_A", 100L))
        .isEqualTo("t1:wf:50:JOB_A:100");
    assertThat(OutboxEventKeyGenerator.forWorkflowTerminal("t1", 50L))
        .isEqualTo("t1:workflow:50:terminal");
    assertThat(OutboxEventKeyGenerator.forManualReplay("t1", "task", 100L))
        .isEqualTo("t1:replay-task:100");
  }

  // ── 不同 scope → 不同 key(零撞) ──────────────────────────────────────

  @Test
  void shouldNeverCollideAcrossScopesForSameTenantAndId() {
    long taskId = 100L;
    String tenant = "t1";
    Set<String> keys = new HashSet<>();
    keys.add(OutboxEventKeyGenerator.forDispatch(tenant, taskId));
    keys.add(OutboxEventKeyGenerator.forFileRedispatch(tenant, taskId));
    keys.add(OutboxEventKeyGenerator.forRetry(tenant, taskId, 1));
    keys.add(OutboxEventKeyGenerator.forReclaim(tenant, taskId, "worker-x"));
    keys.add(OutboxEventKeyGenerator.forCompensation(tenant, "cmp-001", taskId));
    keys.add(OutboxEventKeyGenerator.forManualReplay(tenant, "task", taskId));
    keys.add(OutboxEventKeyGenerator.forManualReplay(tenant, "partition", taskId));
    // 7 scope 同 taskId 同 tenant 应产生 7 个不同 key
    assertThat(keys).hasSize(7);
  }

  @Test
  void shouldRetryAttemptsBeDistinctKeys() {
    // 同 task 多次 retry 必须产出不同 key,否则 dedup 丢失重试
    Set<String> keys = new HashSet<>();
    for (int attempt = 1; attempt <= 5; attempt++) {
      keys.add(OutboxEventKeyGenerator.forRetry("t1", 100L, attempt));
    }
    assertThat(keys).hasSize(5);
  }

  // ── 长度上限 ─────────────────────────────────────────────────────────

  @Test
  void shouldTruncateOversizedKey() {
    // 拼一个超长 nodeCode 让 key > 256
    String longNodeCode = "X".repeat(300);
    String key = OutboxEventKeyGenerator.forWorkflowNodeDispatch("tenant", 1L, longNodeCode, 100L);
    assertThat(key).hasSize(256);
  }

  @Test
  void shouldNotTruncateNormalKey() {
    String key = OutboxEventKeyGenerator.forWorkflowNodeDispatch("tenant", 1L, "NORMAL_NODE", 100L);
    assertThat(key.length()).isLessThan(256);
  }
}
