package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("平台时区提供者:合法时区标识的解析结果,以及非法标识在业务日期计算前快速失败")
class BatchTimezoneProviderTest {

  @Test
  @DisplayName("配置为北美东部时区标识后返回的时区与该标识解析结果一致")
  void shouldReturnConfiguredZone_whenIanaTimezoneValid() {
    BatchTimezoneProperties properties = new BatchTimezoneProperties();
    properties.setDefaultZone("America/New_York");

    assertThat(new BatchTimezoneProvider(properties).defaultZone())
        .isEqualTo(ZoneId.of("America/New_York"));
  }

  @Test
  @DisplayName("时区标识非法时构造即失败并指出配置项与地区标识要求,避免业务日期被静默平移")
  void shouldThrowIllegalState_whenTimezoneInvalid() {
    BatchTimezoneProperties properties = new BatchTimezoneProperties();
    properties.setDefaultZone("not-a-timezone");

    assertThatThrownBy(() -> new BatchTimezoneProvider(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.timezone.default-zone")
        .hasMessageContaining("IANA timezone");
  }
}
