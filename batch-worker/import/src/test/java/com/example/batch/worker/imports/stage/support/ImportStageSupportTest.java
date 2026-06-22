package com.example.batch.worker.imports.stage.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import com.example.batch.worker.imports.domain.ImportJobContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportStageSupportTest {

  @Test
  void resolveChunkSizeUsesTemplateValueWithinMax() {
    ImportJobContext context = new ImportJobContext();
    context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("chunk_size", 5000));
    ImportWorkerConfiguration config = config(2000, 10000);

    assertThat(ImportStageSupport.resolveChunkSize(context, config)).isEqualTo(5000);
  }

  @Test
  void resolveChunkSizeRejectsTemplateValueAboveMax() {
    ImportJobContext context = new ImportJobContext();
    context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("chunk_size", 20000));
    ImportWorkerConfiguration config = config(2000, 10000);

    assertThatThrownBy(() -> ImportStageSupport.resolveChunkSize(context, config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunk_size exceeds maxChunkSize");
  }

  @Test
  void resolveChunkSizeRejectsFallbackAboveMax() {
    assertThatThrownBy(
            () -> ImportStageSupport.resolveChunkSize(new ImportJobContext(), config(20000, 10000)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunk_size exceeds maxChunkSize");
  }

  private static ImportWorkerConfiguration config(int chunkSize, int maxChunkSize) {
    return new ImportWorkerConfiguration(
        "w",
        "IMPORT",
        "tenant-a",
        1000L,
        "topic",
        "group",
        List.of(),
        new ImportWorkerConfiguration.FileProcessing(true, 1000, 1000, chunkSize, maxChunkSize),
        false);
  }
}
