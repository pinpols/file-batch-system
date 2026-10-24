package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationRuleSetMergerTest {

  private final ValidationRuleSetMerger merger =
      new ValidationRuleSetMerger(new ValidationConfigSupport(new ObjectMapper()));

  @Test
  void snakeCaseRuleSetTakesPrecedenceOverCamelCaseAlias() {
    Map<String, Object> templateConfig = Map.of(
        "validation_rule_set", Map.of("mode", "strict"),
        "validationRuleSet", Map.of("mode", "permissive"));

    assertThat(merger.merge(templateConfig)).containsEntry("mode", "strict");
  }
}
