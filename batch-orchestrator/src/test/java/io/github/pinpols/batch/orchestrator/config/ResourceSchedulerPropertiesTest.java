package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.QuotaExceededStrategy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ResourceSchedulerPropertiesTest {

  @Test
  void validDefaultExceededStrategy_shouldBind() {
    ResourceSchedulerProperties properties = bind("REJECT");

    assertThat(properties.getDefaultExceededStrategy()).isEqualTo(QuotaExceededStrategy.REJECT);
  }

  @Test
  void misspelledDefaultExceededStrategy_shouldFailBinding() {
    assertThatThrownBy(() -> bind("QUEU_DEFER"))
        .hasMessageContaining("batch.resource-scheduler.default-exceeded-strategy")
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  private static ResourceSchedulerProperties bind(String strategy) {
    Binder binder =
        new Binder(
            new MapConfigurationPropertySource(
                Map.of("batch.resource-scheduler.default-exceeded-strategy", strategy)));
    return binder
        .bind("batch.resource-scheduler", Bindable.of(ResourceSchedulerProperties.class))
        .get();
  }
}
