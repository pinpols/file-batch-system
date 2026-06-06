package com.example.batch.common.storage;

/** 对象存储操作失败的根异常。 */
public class ObjectStoreException extends RuntimeException {

  public ObjectStoreException(String message) {
    super(message);
  }

  public ObjectStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
