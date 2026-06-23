package io.github.pinpols.batch.common.storage;

/**
 * 权限 / 认证失败（S3 {@code AccessDenied} / {@code InvalidAccessKeyId} / {@code SignatureDoesNotMatch}）。
 */
public class ObjectStoreAccessException extends ObjectStoreException {

  public ObjectStoreAccessException(String message) {
    super(message);
  }

  public ObjectStoreAccessException(String message, Throwable cause) {
    super(message, cause);
  }
}
