package io.github.pinpols.batch.orchestrator.application.service.failure;

/** 持久化约束冲突探测端口。 */
public interface PersistenceConflictDetectionPort {

  /** 异常因果链是否表示指定唯一约束冲突。 */
  boolean isUniqueConstraintViolation(Throwable throwable, String constraintName);
}
