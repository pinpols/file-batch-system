package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置包行解析器:配置编码在写库前统一规范化为小写下划线形式")
class ConfigCodeNormalizationTest {

  @Test
  @DisplayName("四类配置解析器:大写与连字符编码均被规范化为小写下划线")
  void shouldNormalizeConfigCodes_whenParsingRows() {
    assertThat(BusinessCalendarExcelRowParser.parseRow(
                "ta", 1, Map.of("calendar_code", "Default-Calendar"), new ArrayList<>())
            .calendarCode())
        .isEqualTo("default_calendar");
    assertThat(ResourceQueueExcelRowParser.parseRow(
                "ta", 1, Map.of("queue_code", "Fast-Lane"), new ArrayList<>())
            .queueCode())
        .isEqualTo("fast_lane");
    assertThat(BatchWindowExcelRowParser.parseRow(
                "ta", 1, Map.of("window_code", "Night-Window"), new ArrayList<>())
            .windowCode())
        .isEqualTo("night_window");
    assertThat(FileTemplateExcelRowParser.parseRow(
                "ta", 1, Map.of("template_code", "Daily-File"), new ArrayList<>())
            .templateCode())
        .isEqualTo("daily_file");
  }
}
