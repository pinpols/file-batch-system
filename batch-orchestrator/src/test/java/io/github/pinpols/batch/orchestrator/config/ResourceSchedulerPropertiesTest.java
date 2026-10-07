package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.QuotaExceededStrategy;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@DisplayName("资源调度配置属性,校验合法超配额策略可绑定且拼写错误时绑定失败")
class ResourceSchedulerPropertiesTest {

  @Test
  @DisplayName("配置合法超配额策略时绑定成功并解析为对应策略值")
  void validDefaultExceededStrategy_shouldBind() {
    ResourceSchedulerProperties properties = bind("REJECT");

    assertThat(properties.getDefaultExceededStrategy()).isEqualTo(QuotaExceededStrategy.REJECT);
  }

  @Test
  @DisplayName("超配额策略拼写错误时绑定失败,异常信息指明对应配置项且根因为参数非法")
  void misspelledDefaultExceededStrategy_shouldFailBinding() {
    assertThatThrownBy(() -> bind("QUEU_DEFER"))
        .hasMessageContaining("batch.resource-scheduler.default-exceeded-strategy")
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  private static ResourceSchedulerProperties bind(String strategy) {
    Binder binder = new Binder(new MapConfigurationPropertySource(
        Map.of("batch.resource-scheduler.default-exceeded-strategy", strategy)));
    return binder
        .bind("batch.resource-scheduler", Bindable.of(ResourceSchedulerProperties.class))
        .get();
  }
}
