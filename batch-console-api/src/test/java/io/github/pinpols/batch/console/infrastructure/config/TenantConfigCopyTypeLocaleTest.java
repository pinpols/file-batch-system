package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest.ConfigType;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 配置类型属于协议标识，不应随部署主机默认语言而改变解析结果。 */
@DisplayName("配置复制类型解析与默认语言无关")
@ResourceLock("java.util.Locale.default")
class TenantConfigCopyTypeLocaleTest {

  @ParameterizedTest
  @ValueSource(strings = {"tr-TR", "az-AZ"})
  @DisplayName("土耳其语与阿塞拜疆语默认环境仍接受相同配置类型和兼容简写")
  void shouldKeepProtocolTypeParsingIndependentOfDefaultLocale(String language) {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag(language));
      ConfigType channel = ConfigType.fromValue(" file_channel ");
      ConfigType workflow = ConfigType.fromValue("workflow_definition");
      ConfigType job = ConfigType.fromValue("job");
      assertThat(channel).isEqualTo(ConfigType.FILE_CHANNEL);
      assertThat(workflow).isEqualTo(ConfigType.WORKFLOW_DEFINITION);
      assertThat(job).isEqualTo(ConfigType.JOB_DEFINITION);
    } finally {
      Locale.setDefault(previous);
    }
  }
}
