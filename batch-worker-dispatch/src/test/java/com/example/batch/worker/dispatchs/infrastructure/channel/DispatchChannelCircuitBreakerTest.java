package com.example.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DispatchChannelCircuitBreakerTest {

  private static final String CHANNEL = "sftp-outbound";

  private DispatchCircuitBreakerProperties properties;
  private DispatchChannelCircuitBreaker circuitBreaker;

  @BeforeEach
  void setUp() {
    properties = new DispatchCircuitBreakerProperties();
    properties.setEnabled(true);
    properties.setFailureThreshold(3);
    properties.setCooldownMillis(60_000L);
    circuitBreaker = new DispatchChannelCircuitBreaker(properties);
  }

  @Test
  void shouldAllowWhenNoFailuresRecorded() {
    assertThat(circuitBreaker.allow(CHANNEL)).isTrue();
  }

  @Test
  void shouldAllowBelowFailureThreshold() {
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordFailure(CHANNEL);
    // threshold is 3, only 2 failures so far
    assertThat(circuitBreaker.allow(CHANNEL)).isTrue();
  }

  @Test
  void shouldOpenCircuitAtFailureThreshold() {
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordFailure(CHANNEL); // reaches threshold
    assertThat(circuitBreaker.allow(CHANNEL)).isFalse();
  }

  @Test
  void shouldCountOpenCircuitsCorrectly() {
    triggerOpen("ch-1");
    triggerOpen("ch-2");
    assertThat(circuitBreaker.currentOpenCircuits()).isEqualTo(2);
  }

  @Test
  void shouldResetOnSuccess() {
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordSuccess(CHANNEL);
    // failure counter reset; allow should return true
    assertThat(circuitBreaker.allow(CHANNEL)).isTrue();
    // new failures should not carry over the old count
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordFailure(CHANNEL);
    assertThat(circuitBreaker.allow(CHANNEL)).isTrue(); // still below threshold
  }

  @Test
  void shouldAllowAfterCooldownExpires() throws InterruptedException {
    // Use a very short cooldown for this test
    properties.setCooldownMillis(10L);
    DispatchChannelCircuitBreaker shortCooldown = new DispatchChannelCircuitBreaker(properties);

    triggerOpenWith(shortCooldown, CHANNEL);
    assertThat(shortCooldown.allow(CHANNEL)).isFalse();

    Thread.sleep(50); // wait for cooldown
    assertThat(shortCooldown.allow(CHANNEL)).isTrue();
    assertThat(shortCooldown.currentOpenCircuits()).isEqualTo(0);
  }

  @Test
  void shouldBypassCircuitBreakerWhenDisabled() {
    properties.setEnabled(false);
    DispatchChannelCircuitBreaker disabled = new DispatchChannelCircuitBreaker(properties);

    // Even after many failures, should always allow
    for (int i = 0; i < 10; i++) {
      disabled.recordFailure(CHANNEL);
    }
    assertThat(disabled.allow(CHANNEL)).isTrue();
    assertThat(disabled.currentOpenCircuits()).isEqualTo(0);
  }

  @Test
  void shouldIsolateFailuresByChannelKey() {
    triggerOpen("ch-bad");

    // a different channel is unaffected
    assertThat(circuitBreaker.allow("ch-good")).isTrue();
  }

  // --- helpers ---

  private void triggerOpen(String key) {
    triggerOpenWith(circuitBreaker, key);
  }

  private void triggerOpenWith(DispatchChannelCircuitBreaker cb, String key) {
    for (int i = 0; i < properties.getFailureThreshold(); i++) {
      cb.recordFailure(key);
    }
  }
}
