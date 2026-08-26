package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 手工 launch 的本地 admission 闸门。
 *
 * <p>Trigger 的异步写入仍必须先落库再返回；当应用线程在等待数据库连接或事务时，继续接收请求只会把
 * 排队压力转移到 Servlet 线程和连接池。该闸门在进入 launch 事务前快速拒绝超出本实例预算的请求，
 * 不参与 Quartz、outbox relay 或内部 scheduled launch，跨实例总量仍由租户 quota 负责。
 */
@Component
@RequiredArgsConstructor
public class TriggerApiAdmissionGuard {

  private final TriggerRuntimeProperties properties;
  private final Object admissionLock = new Object();
  private int activeRequests;

  public <T> T execute(Supplier<T> action) {
    acquire();
    try {
      return action.get();
    } finally {
      release();
    }
  }

  private void acquire() {
    synchronized (admissionLock) {
      int limit = Math.max(1, properties.getApiLaunchMaxConcurrency());
      if (activeRequests >= limit) {
        throw BizException.of(
            ResultCode.RATE_LIMITED,
            "error.common.rate_limited_detail",
            "trigger launch admission capacity reached");
      }
      activeRequests++;
    }
  }

  private void release() {
    synchronized (admissionLock) {
      activeRequests--;
    }
  }
}
