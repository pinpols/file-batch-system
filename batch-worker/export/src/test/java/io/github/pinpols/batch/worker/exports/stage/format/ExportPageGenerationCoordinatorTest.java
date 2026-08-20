package io.github.pinpols.batch.worker.exports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExportPageGenerationCoordinatorTest {

  @Test
  void writesRowsAcrossPagesWithMonotonicRowIndexes() throws Exception {
    ExportDataPlugin plugin = mock(ExportDataPlugin.class);
    ExportDataContext dataContext =
        new ExportDataContext("tenant-a", "job-a", "batch-a", "template-a", Map.of(), Map.of());
    when(plugin.loadDetailPage(dataContext, 7L, 2, null))
        .thenReturn(new ExportDataPlugin.DetailPage(List.of(Map.of("id", 1)), "next"));
    when(plugin.loadDetailPage(dataContext, 7L, 2, "next"))
        .thenReturn(new ExportDataPlugin.DetailPage(List.of(Map.of("id", 2)), null));
    ExportFormatContext context = ExportFormatContext.builder()
        .batch(Map.of("batchNo", "batch-a"))
        .batchId(7L)
        .pageSize(2)
        .dataPlugin(plugin)
        .dataCtx(dataContext)
        .build();
    List<Long> rowIndexes = new ArrayList<>();
    List<Map<String, Object>> rows = new ArrayList<>();

    long count = ExportPageGenerationCoordinator.generatePaged(
        context, null, null, (batch, detail, rowIndex) -> {
          rows.add(detail);
          rowIndexes.add(rowIndex);
        });

    assertThat(count).isEqualTo(2);
    assertThat(rowIndexes).containsExactly(0L, 1L);
    assertThat(rows).containsExactly(Map.of("id", 1), Map.of("id", 2));
    verify(plugin, times(2)).loadDetailPage(eq(dataContext), eq(7L), eq(2), any());
  }

  @Test
  void usesPrefetchedPageAndDoesNotQueryItAgain() throws Exception {
    ExportDataPlugin plugin = mock(ExportDataPlugin.class);
    ExportDataContext dataContext =
        new ExportDataContext("tenant-a", "job-a", "batch-a", "template-a", Map.of(), Map.of());
    ExportDataPlugin.DetailPage firstPage =
        new ExportDataPlugin.DetailPage(List.of(Map.of("id", 1)), null);
    ExportFormatContext context = ExportFormatContext.builder()
        .batch(Map.of())
        .batchId(7L)
        .pageSize(2)
        .dataPlugin(plugin)
        .dataCtx(dataContext)
        .build();

    long count = ExportPageGenerationCoordinator.generatePaged(
        context, firstPage, null, (batch, detail, rowIndex) -> {});

    assertThat(count).isEqualTo(1);
    verify(plugin, times(0)).loadDetailPage(any(), any(), any(Integer.class), any());
  }
}
