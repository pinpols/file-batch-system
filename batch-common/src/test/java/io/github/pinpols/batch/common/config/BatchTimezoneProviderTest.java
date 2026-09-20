package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BatchTimezoneProviderTest {

  @Test
  void usesConfiguredIanaTimezone() {
    BatchTimezoneProperties properties = new BatchTimezoneProperties();
    properties.setDefaultZone("America/New_York");

    assertThat(new BatchTimezoneProvider(properties).defaultZone())
        .isEqualTo(ZoneId.of("America/New_York"));
  }

  @Test
  void rejectsInvalidPlatformTimezoneInsteadOfSilentlyShiftingBusinessDate() {
    BatchTimezoneProperties properties = new BatchTimezoneProperties();
    properties.setDefaultZone("not-a-timezone");

    assertThatThrownBy(() -> new BatchTimezoneProvider(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.timezone.default-zone")
        .hasMessageContaining("IANA timezone");
  }
}
