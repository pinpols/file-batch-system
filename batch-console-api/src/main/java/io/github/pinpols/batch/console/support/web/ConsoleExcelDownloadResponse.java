package io.github.pinpols.batch.console.support.web;

import io.github.pinpols.batch.console.application.contract.response.ExcelDownload;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Excel 应用结果到 HTTP 流式下载的适配。 */
public final class ConsoleExcelDownloadResponse {
  private static final MediaType EXCEL = MediaType.parseMediaType(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private ConsoleExcelDownloadResponse() {}

  public static ResponseEntity<StreamingResponseBody> from(ExcelDownload download) {
    String disposition = ContentDisposition.attachment().filename(download.fileName()).build().toString();
    StreamingResponseBody body = download.writer()::writeTo;
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
        .contentType(EXCEL)
        .body(body);
  }
}
