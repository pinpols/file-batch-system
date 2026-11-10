package io.github.pinpols.batch.worker.exports.stage.format;

/** 实际导出记录数超过配置上限，阻止继续生成和登记文件。 */
public final class ExportRowLimitExceededException extends RuntimeException {

  private final long observedRows;
  private final long maxRows;

  public ExportRowLimitExceededException(long observedRows, long maxRows) {
    super("export row count " + observedRows + " exceeds limit " + maxRows);
    this.observedRows = observedRows;
    this.maxRows = maxRows;
  }

  public long observedRows() {
    return observedRows;
  }

  public long maxRows() {
    return maxRows;
  }
}
