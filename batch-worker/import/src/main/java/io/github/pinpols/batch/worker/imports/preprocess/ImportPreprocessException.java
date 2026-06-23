package io.github.pinpols.batch.worker.imports.preprocess;

public class ImportPreprocessException extends RuntimeException {

  private final String errorCode;

  public ImportPreprocessException(String errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ImportPreprocessException(String errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }
}
