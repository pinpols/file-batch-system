package com.example.batch.common.storage;

/** 对象不存在（S3 {@code NoSuchKey} / FS {@code NoSuchFile}）。 */
public class ObjectNotFoundException extends ObjectStoreException {

  public ObjectNotFoundException(String message) {
    super(message);
  }

  public ObjectNotFoundException(String message, Throwable cause) {
    super(message, cause);
  }
}
