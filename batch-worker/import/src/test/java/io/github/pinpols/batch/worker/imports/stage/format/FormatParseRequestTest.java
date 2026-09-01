package io.github.pinpols.batch.worker.imports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FormatParseRequestTest {

  @Test
  void keepsSpoolReaderOpenUntilCallerClosesIt() throws Exception {
    Path spool = Files.createTempFile("format-parse-request-", ".csv");
    try {
      Files.writeString(spool, "header\nvalue\n", StandardCharsets.UTF_8);
      FormatParseRequest request =
          new FormatParseRequest(null, null, null, null, false, spool, StandardCharsets.UTF_8);

      try (var reader = request.openTextReader()) {
        assertThat(reader.readLine()).isEqualTo("header");
        assertThat(reader.readLine()).isEqualTo("value");
      }
    } finally {
      Files.deleteIfExists(spool);
    }
  }
}
