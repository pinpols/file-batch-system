package io.github.pinpols.batch.worker.processes.sql;

/** SQL transform plugin 的共享配置键与内部表名。 */
final class SqlTransformComputeConstants {

  static final String METADATA_PARAM_PREFIX = "metadata_";
  static final String STAGING_TABLE = "batch.process_staging";

  private SqlTransformComputeConstants() {}
}
