package io.github.pinpols.batch.sdk.internal;

import java.io.IOException;

/**
 * 平台 {@code /internal/*} 调用收到非 2xx 响应。携带 HTTP {@code statusCode} 让上层(dispatcher / scheduler)分类处理:
 *
 * <ul>
 *   <li>{@link #isAuthError()} — 401/403,鉴权失败,不可重试,SDK 应 fail-fast
 *   <li>{@link #isConflict()} — 409,典型 peer-already-claimed,放弃即可,不报告失败
 *   <li>{@link #isServerError()} — 5xx,平台侧问题,可指数退避重试
 *   <li>否则 — 其它 4xx,客户端构造问题,重试无益,放弃并 WARN
 * </ul>
 *
 * <p>仅由 {@link PlatformHttpClient} 抛出;传输层错误(socket / timeout)仍走通用 {@link IOException}。
 */
public class PlatformHttpException extends IOException {

  private final int statusCode;

  public PlatformHttpException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }

  public boolean isAuthError() {
    return statusCode == 401 || statusCode == 403;
  }

  public boolean isConflict() {
    return statusCode == 409;
  }

  public boolean isServerError() {
    return statusCode >= 500 && statusCode < 600;
  }

  public boolean isClientError() {
    return statusCode >= 400 && statusCode < 500;
  }
}
