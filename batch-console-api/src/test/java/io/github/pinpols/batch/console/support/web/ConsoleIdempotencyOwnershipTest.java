package io.github.pinpols.batch.console.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.console.application.idempotency.ConsoleDurableIdempotencyStore;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.TaskScheduler;

class ConsoleIdempotencyOwnershipTest {
  private final ExpiringStore store = new ExpiringStore();
  private final ConsoleDurableIdempotencyStore durable = mock(ConsoleDurableIdempotencyStore.class);
  private final ConsoleIdempotencyInterceptor interceptor = new ConsoleIdempotencyInterceptor(
      store, durable, new BatchSecurityProperties(), mock(TaskScheduler.class));

  @Test
  void renewalKeepsLongRequestExclusiveAndStopsAfterCompletion() throws Exception {
    var first = request();
    assertThat(interceptor.preHandle(first, response(200), this)).isTrue();
    for (int i = 0; i < 10; i++) {
      store.now += 10_000;
      interceptor.renewPendingLeases();
      var conflict = response(200);
      assertThat(interceptor.preHandle(request(), conflict, this)).isFalse();
      assertThat(conflict.getStatus()).isEqualTo(409);
    }
    interceptor.afterCompletion(first, response(200), this, null);
    verify(durable).markCompleted(anyString(), anyString());
    int mutations = store.casCalls;
    interceptor.renewPendingLeases();
    assertThat(store.casCalls).isEqualTo(mutations);
    assertThat(store.data.values()).allMatch(entry -> entry.value().equals("DONE"));
  }

  @Test
  void failedOldOwnerCannotDeleteOrRenewNewOwnersReservation() throws Exception {
    var first = request();
    assertThat(interceptor.preHandle(first, response(200), this)).isTrue();
    store.now += 31_000;
    var second = request();
    assertThat(interceptor.preHandle(second, response(200), this)).isTrue();
    interceptor.renewPendingLeases();
    interceptor.afterCompletion(first, response(500), this, null);
    assertThat(interceptor.preHandle(request(), response(200), this)).isFalse();
    interceptor.afterCompletion(second, response(500), this, null);
    assertThat(interceptor.preHandle(request(), response(200), this)).isTrue();
  }

  @Test
  void successfulOldOwnerCannotOverwriteNewOwnersMarker() throws Exception {
    var first = request();
    assertThat(interceptor.preHandle(first, response(200), this)).isTrue();
    store.now += 31_000;
    var second = request();
    assertThat(interceptor.preHandle(second, response(200), this)).isTrue();
    interceptor.afterCompletion(first, response(200), this, null);
    assertThat(store.data.values())
        .allMatch(entry -> entry.value().equals(second.getAttribute("console.idempotency.owner")));
  }

  private MockHttpServletRequest request() {
    var request = new MockHttpServletRequest("POST", "/api/console/probe");
    request.addHeader("X-Tenant-Id", "ta");
    request.addHeader("Idempotency-Key", "same-key");
    return request;
  }

  private MockHttpServletResponse response(int status) {
    var response = new MockHttpServletResponse();
    response.setStatus(status);
    return response;
  }

  private static class ExpiringStore implements ConsoleIdempotencyStore {
    private record Entry(String value, long expires) {}

    private final Map<String, Entry> data = new HashMap<>();
    private long now;
    private int casCalls;

    public String get(String key) {
      Entry entry = data.get(key);
      if (entry != null && entry.expires() <= now) {
        data.remove(key);
        return null;
      }
      return entry == null ? null : entry.value();
    }

    public Boolean setIfAbsent(String key, String value, Duration ttl) {
      if (get(key) != null) return false;
      data.put(key, new Entry(value, now + ttl.toMillis()));
      return true;
    }

    public boolean compareAndSet(String key, String expected, String value, Duration ttl) {
      casCalls++;
      if (!Objects.equals(get(key), expected)) return false;
      data.put(key, new Entry(value, now + ttl.toMillis()));
      return true;
    }

    public boolean deleteIfValue(String key, String expected) {
      if (!Objects.equals(get(key), expected)) return false;
      data.remove(key);
      return true;
    }
  }
}
