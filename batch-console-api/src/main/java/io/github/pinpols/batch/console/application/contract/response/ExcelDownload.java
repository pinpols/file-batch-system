package io.github.pinpols.batch.console.application.contract.response;

import java.io.IOException;
import java.io.OutputStream;

/** 延迟写入的 Excel 下载内容。 */
public record ExcelDownload(String fileName, Writer writer) {
  @FunctionalInterface
  public interface Writer {
    void writeTo(OutputStream output) throws IOException;
  }
}
