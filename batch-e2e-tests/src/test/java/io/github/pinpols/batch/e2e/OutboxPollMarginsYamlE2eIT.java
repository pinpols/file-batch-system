package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.e2e.apps.E2eOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.config.OutboxProperties;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 守护 {@code batch-e2e-tests/src/test/resources/application-test.yml} 中的 Outbox 轮询边界。
 *
 * <p>若只设置 {@code batch.outbox.poll-interval-millis} 而保留默认 {@code min-poll-interval-millis=200}，
 * {@link io.github.pinpols.batch.orchestrator.infrastructure.mq.OutboxPollScheduler} 仍以 200ms
 * 下限自调度；库未就绪时会高频刷包含 {@code ERROR: relation "batch.outbox_event" does not exist} 的 WARN（PostgreSQL
 * 原文嵌在消息里）。
 */
@SpringBootTest(
    classes = E2eOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "e2e"})
@Tag("e2e")
class OutboxPollMarginsYamlE2eIT extends AbstractIntegrationTest {

  @Autowired private OutboxProperties outboxProperties;

  @Test
  void applicationTestYamlAlignsOutboxMinAndMaxPollInterval() {
    assertThat(outboxProperties.getPollIntervalMillis()).isEqualTo(600_000L);
    assertThat(outboxProperties.getMinPollIntervalMillis()).isEqualTo(600_000L);
  }
}
