package com.example.batch.sdk.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkTypedTaskHandlerTest {

  record ImportRequest(String sourcePath, int batchSize, LocalDate bizDate) {}

  record ImportResult(int rows, String status) {}

  static class ImportHandler extends SdkTypedTaskHandler<ImportRequest, ImportResult> {
    ImportRequest seen;

    @Override
    public String taskType() {
      return "tenant_xyz_import";
    }

    @Override
    protected ImportResult handle(ImportRequest req, SdkTaskContext ctx) {
      this.seen = req;
      return new ImportResult(req.batchSize() * 2, "DONE");
    }

    @Override
    protected String successMessage(ImportResult output) {
      return "imported " + output.rows() + " rows";
    }
  }

  private static SdkTaskContext ctxWith(Map<String, Object> params) {
    return new SdkTaskContext("t1", "job-1", "ti-1", 42L, "w1", params, Map.of());
  }

  @Test
  void deserializesParametersIntoTypedInput() {
    ImportHandler handler = new ImportHandler();

    SdkTaskResult result =
        handler.execute(
            ctxWith(
                Map.of("sourcePath", "/data/in.csv", "batchSize", 500, "bizDate", "2026-06-01")));

    assertThat(handler.seen.sourcePath()).isEqualTo("/data/in.csv");
    assertThat(handler.seen.batchSize()).isEqualTo(500);
    assertThat(handler.seen.bizDate()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(result.success()).isTrue();
  }

  @Test
  void serializesTypedOutputIntoResultOutputMap() {
    ImportHandler handler = new ImportHandler();

    SdkTaskResult result = handler.execute(ctxWith(Map.of("sourcePath", "/x", "batchSize", 10)));

    assertThat(result.output()).containsEntry("rows", 20).containsEntry("status", "DONE");
    assertThat(result.message()).isEqualTo("imported 20 rows");
  }

  @Test
  void invalidParametersFailWithoutEnteringBusiness() {
    ImportHandler handler = new ImportHandler();

    SdkTaskResult result = handler.execute(ctxWith(Map.of("batchSize", "not-a-number")));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("invalid parameters for taskType=tenant_xyz_import");
    assertThat(handler.seen).isNull();
  }

  @Test
  void emptyParametersDeserializeToNullableFields() {
    ImportHandler handler = new ImportHandler();

    SdkTaskResult result = handler.execute(ctxWith(Map.of()));

    assertThat(result.success()).isTrue();
    assertThat(handler.seen.sourcePath()).isNull();
    assertThat(handler.seen.batchSize()).isZero();
  }

  @Test
  void nullOutputYieldsEmptyOutputMap() {
    SdkTypedTaskHandler<ImportRequest, ImportResult> handler =
        new SdkTypedTaskHandler<>() {
          @Override
          public String taskType() {
            return "noop";
          }

          @Override
          protected ImportResult handle(ImportRequest req, SdkTaskContext ctx) {
            return null;
          }
        };

    SdkTaskResult result = handler.execute(ctxWith(Map.of("sourcePath", "/x", "batchSize", 1)));

    assertThat(result.success()).isTrue();
    assertThat(result.output()).isEmpty();
    assertThat(result.message()).isEqualTo("ok");
  }
}
