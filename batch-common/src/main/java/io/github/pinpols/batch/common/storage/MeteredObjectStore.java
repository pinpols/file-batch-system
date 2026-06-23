package io.github.pinpols.batch.common.storage;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 指标装饰层:给每个对象存储操作打点(延迟 + 成功/失败计数),挂在最外层(加密/raw 之外)。计量名 {@code batch.objectstore.op},按 {@code
 * operation}(put/get/list/...)与 {@code outcome}(success/error)分标签。
 *
 * <p>对象存储是热路径(ingress/export/dispatch/治理),per-op 指标便于发现后端抖动/慢调用——尤其在评估更换 MinIO 后端时,
 * 可直接对比新旧后端的延迟分布。纯观测,不改语义:任何方法抛出原异常照常向上传播,仅在 finally 记录耗时与结果标签。
 */
public class MeteredObjectStore implements BatchObjectStore {

  private static final String TIMER = "batch.objectstore.op";

  private final BatchObjectStore delegate;
  private final MeterRegistry registry;

  public MeteredObjectStore(BatchObjectStore delegate, MeterRegistry registry) {
    this.delegate = delegate;
    this.registry = registry;
  }

  private void timedRun(String op, Runnable action) {
    timed(
        op,
        () -> {
          action.run();
          return null;
        });
  }

  private <T> T timed(String op, Supplier<T> action) {
    long start = System.nanoTime();
    String outcome = "success";
    try {
      return action.get();
    } catch (RuntimeException ex) {
      outcome = "error";
      throw ex;
    } finally {
      Timer.builder(TIMER)
          .tag("operation", op)
          .tag("outcome", outcome)
          .register(registry)
          .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  @Override
  public void put(String bucket, String key, InputStream in, long size, String contentType) {
    timedRun("put", () -> delegate.put(bucket, key, in, size, contentType));
  }

  @Override
  public void copy(String bucket, String srcKey, String dstKey) {
    timedRun("copy", () -> delegate.copy(bucket, srcKey, dstKey));
  }

  @Override
  public void delete(String bucket, String key) {
    timedRun("delete", () -> delegate.delete(bucket, key));
  }

  @Override
  public void deleteMany(String bucket, Collection<String> keys) {
    timedRun("deleteMany", () -> delegate.deleteMany(bucket, keys));
  }

  @Override
  public InputStream get(String bucket, String key) {
    return timed("get", () -> delegate.get(bucket, key));
  }

  @Override
  public InputStream getFrom(String bucket, String key, long offset) {
    return timed("getFrom", () -> delegate.getFrom(bucket, key, offset));
  }

  @Override
  public boolean supportsRangeRead() {
    return delegate.supportsRangeRead();
  }

  @Override
  public long statSize(String bucket, String key) {
    return timed("statSize", () -> delegate.statSize(bucket, key));
  }

  @Override
  public boolean exists(String bucket, String key) {
    return timed("exists", () -> delegate.exists(bucket, key));
  }

  @Override
  public ObjectListing list(String bucket, String prefix, String afterMarker, int maxKeys) {
    return timed("list", () -> delegate.list(bucket, prefix, afterMarker, maxKeys));
  }

  @Override
  public String presign(String bucket, String key, Duration ttl) {
    return timed("presign", () -> delegate.presign(bucket, key, ttl));
  }

  @Override
  public boolean supportsPresignPut() {
    return delegate.supportsPresignPut();
  }

  @Override
  public String presignPut(String bucket, String key, Duration ttl, String contentType) {
    return timed("presignPut", () -> delegate.presignPut(bucket, key, ttl, contentType));
  }
}
