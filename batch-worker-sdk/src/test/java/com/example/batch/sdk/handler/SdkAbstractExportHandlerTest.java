package com.example.batch.sdk.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-036 — Export 模板基类单测,经 {@link SdkAbstractTaskHandler#execute} 走全模板序。 */
class SdkAbstractExportHandlerTest {

  private static SdkTaskContext ctx() {
    return new SdkTaskContext("tx", "job", "ti", 1L, "w-1", Map.of(), Map.of());
  }

  /** 可配置的探针子类:记录 formatRow 收到的行 + streamRows 收到的 query,各钩子可注入异常。 */
  private static final class ProbeExportHandler extends SdkAbstractExportHandler<String> {
    final List<String> formatted = new ArrayList<>();
    final AtomicReference<String> seenQuery = new AtomicReference<>();
    boolean writeOutCalled = false;

    private final List<String> rows;
    private final String query;
    private final SdkTaskResult writeOutResult;
    private final RuntimeException openSinkEx;
    private final RuntimeException streamRowsEx;
    private final RuntimeException formatRowEx;
    private final RuntimeException writeOutEx;

    private ProbeExportHandler(
        List<String> rows,
        String query,
        SdkTaskResult writeOutResult,
        RuntimeException openSinkEx,
        RuntimeException streamRowsEx,
        RuntimeException formatRowEx,
        RuntimeException writeOutEx) {
      this.rows = rows;
      this.query = query;
      this.writeOutResult = writeOutResult;
      this.openSinkEx = openSinkEx;
      this.streamRowsEx = streamRowsEx;
      this.formatRowEx = formatRowEx;
      this.writeOutEx = writeOutEx;
    }

    static ProbeExportHandler ofRows(List<String> rows, SdkTaskResult writeOutResult) {
      return new ProbeExportHandler(
          rows, "SELECT * FROM t", writeOutResult, null, null, null, null);
    }

    @Override
    public String taskType() {
      return "tenant_xyz_export";
    }

    @Override
    protected void openSink(SdkTaskContext c) {
      if (openSinkEx != null) {
        throw openSinkEx;
      }
    }

    @Override
    protected String buildQuery(SdkTaskContext c) {
      return query;
    }

    @Override
    protected Iterator<String> streamRows(SdkTaskContext c, String q) {
      seenQuery.set(q);
      if (streamRowsEx != null) {
        throw streamRowsEx;
      }
      return rows.iterator();
    }

    @Override
    protected void formatRow(SdkTaskContext c, String row) {
      if (formatRowEx != null) {
        throw formatRowEx;
      }
      formatted.add(row);
    }

    @Override
    protected SdkTaskResult writeOut(SdkTaskContext c, SdkRowResult counts) {
      writeOutCalled = true;
      if (writeOutEx != null) {
        throw writeOutEx;
      }
      return writeOutResult;
    }
  }

  private static List<String> rows(int n) {
    return IntStream.range(0, n).mapToObj(i -> "row-" + i).toList();
  }

  @Test
  @DisplayName("50 行 → formatRow 调 50 次,writeOut 返回的 result 生效,success=50")
  void exports50Rows_whenWriteOutReturnsResult() {
    // arrange
    SdkTaskResult custom = SdkTaskResult.ok("done", Map.of("uri", "s3://b/out.csv"));
    ProbeExportHandler h = ProbeExportHandler.ofRows(rows(50), custom);

    // act
    SdkTaskResult r = h.execute(ctx());

    // assert
    assertThat(h.formatted).hasSize(50);
    assertThat(r.success()).isTrue();
    assertThat(r.message()).isEqualTo("done");
    assertThat(r.output()).containsEntry("uri", "s3://b/out.csv");
    assertThat(h.writeOutCalled).isTrue();
  }

  @Test
  @DisplayName("writeOut 返回 null → 框架兜底 ok(\"exported 50 rows\") + counts output")
  void fallsBackToDefaultResult_whenWriteOutReturnsNull() {
    // arrange
    ProbeExportHandler h = ProbeExportHandler.ofRows(rows(50), null);

    // act
    SdkTaskResult r = h.execute(ctx());

    // assert
    assertThat(r.success()).isTrue();
    assertThat(r.message()).isEqualTo("exported 50 rows");
    assertThat(r.output()).containsEntry("success", 50L).containsEntry("total", 50L);
  }

  @Test
  @DisplayName("0 行 → formatRow 不调,success=0,writeOut 仍调")
  void exportsZeroRows_whenNoRows() {
    // arrange
    ProbeExportHandler h = ProbeExportHandler.ofRows(rows(0), null);

    // act
    SdkTaskResult r = h.execute(ctx());

    // assert
    assertThat(h.formatted).isEmpty();
    assertThat(h.writeOutCalled).isTrue();
    assertThat(r.success()).isTrue();
    assertThat(r.message()).isEqualTo("exported 0 rows");
    assertThat(r.output()).containsEntry("success", 0L).containsEntry("total", 0L);
  }

  @Test
  @DisplayName("buildQuery 结果透传给 streamRows")
  void passesBuildQueryResultToStreamRows() {
    // arrange
    ProbeExportHandler h = ProbeExportHandler.ofRows(rows(3), null);

    // act
    h.execute(ctx());

    // assert
    assertThat(h.seenQuery.get()).isEqualTo("SELECT * FROM t");
  }

  @Test
  @DisplayName("streamRows 抛异常 → fail")
  void fails_whenStreamRowsThrows() {
    // arrange
    ProbeExportHandler h =
        new ProbeExportHandler(
            rows(5), "q", null, null, new IllegalStateException("stream boom"), null, null);

    // act
    SdkTaskResult r = h.execute(ctx());

    // assert
    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("stream boom");
    assertThat(h.formatted).isEmpty();
  }

  @Test
  @DisplayName("formatRow 抛异常 → fail")
  void fails_whenFormatRowThrows() {
    // arrange
    ProbeExportHandler h =
        new ProbeExportHandler(
            rows(5), "q", null, null, null, new IllegalStateException("format boom"), null);

    // act
    SdkTaskResult r = h.execute(ctx());

    // assert
    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("format boom");
    assertThat(h.writeOutCalled).isFalse();
  }

  @Test
  @DisplayName("writeOut 抛异常 → fail")
  void fails_whenWriteOutThrows() {
    // arrange
    ProbeExportHandler h =
        new ProbeExportHandler(
            rows(5), "q", null, null, null, null, new IllegalStateException("writeout boom"));

    // act
    SdkTaskResult r = h.execute(ctx());

    // assert
    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("writeout boom");
    assertThat(h.formatted).hasSize(5);
  }

  @Test
  @DisplayName("openSink 抛异常 → 后续都不调,fail")
  void fails_whenOpenSinkThrows() {
    // arrange
    ProbeExportHandler h =
        new ProbeExportHandler(
            rows(5), "q", null, new IllegalStateException("sink boom"), null, null, null);

    // act
    SdkTaskResult r = h.execute(ctx());

    // assert
    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("sink boom");
    assertThat(h.seenQuery.get()).isNull();
    assertThat(h.formatted).isEmpty();
    assertThat(h.writeOutCalled).isFalse();
  }
}
