package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("校验规则集字段别名的优先级")
class ValidationRuleSetMergerTest {

  private final ValidationRuleSetMerger merger =
      new ValidationRuleSetMerger(new ValidationConfigSupport(new ObjectMapper()));

  @DisplayName("同时提供两种别名时优先使用下划线规则集")
  @Test
  void shouldPreferSnakeCaseRuleSet_whenBothAliasesPresent() {
    Map<String, Object> templateConfig = Map.of(
        "validation_rule_set", Map.of("mode", "strict"),
        "validationRuleSet", Map.of("mode", "permissive"));

    assertThat(merger.merge(templateConfig)).containsEntry("mode", "strict");
  }
}
