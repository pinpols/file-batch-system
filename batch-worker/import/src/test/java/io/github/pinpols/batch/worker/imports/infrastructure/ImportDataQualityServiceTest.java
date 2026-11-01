package io.github.pinpols.batch.worker.imports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationConfigSupport;
import io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationRuleSetMerger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("导入数据质量校验会话")
class ImportDataQualityServiceTest {

  @Test
  @DisplayName("校验会话保留缺失的原文并转换非字符串原文")
  void beginValidation_retainsNullablePayload() {
    ValidationConfigSupport configSupport = new ValidationConfigSupport(new ObjectMapper());
    ValidationRuleSetMerger merger = new ValidationRuleSetMerger(configSupport);
    ImportDataQualityService service =
        new ImportDataQualityService(configSupport, merger, null, null, null);
    ImportJobContext context = new ImportJobContext();

    assertThat(service.beginValidation(null, 0, List.of()).normalizedPayload()).isNull();
    assertThat(service.beginValidation(context, 0, List.of()).normalizedPayload())
        .isNull();

    context.getAttributes().put(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD, 42);
    assertThat(service.beginValidation(context, 1, List.of()).normalizedPayload())
        .isEqualTo("42");
  }
}
