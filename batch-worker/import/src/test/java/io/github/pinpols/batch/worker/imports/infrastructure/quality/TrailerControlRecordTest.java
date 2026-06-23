package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrailerControlRecordTest {

  @Test
  @DisplayName("DELIMITED trailer:按 index 抽出声明记录数 + 控制总额")
  void parsesDelimitedTrailer() {
    Map<String, Object> tmpl =
        Map.of("present", true, "delimiter", ",", "recordCountIndex", 1, "controlTotalIndex", 2);
    TrailerControlRecord t = TrailerControlRecord.parse("T,1000,50000000.00", tmpl);
    assertThat(t.declaredRecordCount()).isEqualTo(1000L);
    assertThat(t.declaredControlTotal()).isEqualByComparingTo(new BigDecimal("50000000.00"));
    assertThat(t.isPresent()).isTrue();
  }

  @Test
  @DisplayName("present=false 或空模板 → 空记录(不参与校验)")
  void emptyWhenNotPresent() {
    assertThat(TrailerControlRecord.parse("T,1,2", Map.of("present", false)).isPresent()).isFalse();
    assertThat(TrailerControlRecord.parse("T,1,2", Map.of()).isPresent()).isFalse();
    assertThat(TrailerControlRecord.parse(null, Map.of("present", true)).isPresent()).isFalse();
  }

  @Test
  @DisplayName("缺字段 / 非数字 → 对应值 null(不抛),交校验侧处置")
  void nullOnMissingOrNonNumeric() {
    Map<String, Object> tmpl =
        Map.of("present", true, "recordCountIndex", 5, "controlTotalIndex", 1);
    TrailerControlRecord t = TrailerControlRecord.parse("T,notanumber", tmpl);
    assertThat(t.declaredRecordCount()).isNull(); // index 5 越界
    assertThat(t.declaredControlTotal()).isNull(); // 非数字
  }

  @Test
  @DisplayName("自定义分隔符(|)")
  void customDelimiter() {
    Map<String, Object> tmpl = Map.of("present", true, "delimiter", "|", "recordCountIndex", 0);
    assertThat(TrailerControlRecord.parse("42|x", tmpl).declaredRecordCount()).isEqualTo(42L);
  }
}
