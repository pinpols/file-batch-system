package io.github.pinpols.batch.worker.exports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-041 Phase1.4:出站 trailer 控制记录构造(纯函数)。 */
class OutboundTrailerRecordTest {

  @Test
  @DisplayName("present=true 启用;false / 空模板不启用")
  void enabled_respectsPresent() {
    assertThat(OutboundTrailerRecord.enabled(Map.of("present", true))).isTrue();
    assertThat(OutboundTrailerRecord.enabled(Map.of("present", false))).isFalse();
    assertThat(OutboundTrailerRecord.enabled(Map.of())).isFalse();
    assertThat(OutboundTrailerRecord.enabled(null)).isFalse();
  }

  @Test
  @DisplayName("amountField / amount_field 解析,缺省 null")
  void amountField_resolution() {
    assertThat(OutboundTrailerRecord.amountField(Map.of("amountField", "amount")))
        .isEqualTo("amount");
    assertThat(OutboundTrailerRecord.amountField(Map.of("amount_field", "amt"))).isEqualTo("amt");
    assertThat(OutboundTrailerRecord.amountField(Map.of("present", true))).isNull();
  }

  @Test
  @DisplayName("按 index 放 recordType/recordCount/controlTotal,fieldCount 由最大 index 推断")
  void buildValues_placesByIndex() {
    Map<String, Object> tmpl =
        Map.of(
            "recordType", "T", "recordTypeIndex", 0, "recordCountIndex", 1, "controlTotalIndex", 2);
    assertThat(OutboundTrailerRecord.buildValues(tmpl, 1000L, new BigDecimal("50000000.00")))
        .containsExactly("T", "1000", "50000000.00");
  }

  @Test
  @DisplayName("controlTotal=null(只写笔数)→ 控制总额列留空")
  void buildValues_countOnly() {
    Map<String, Object> tmpl =
        Map.of("recordTypeIndex", 0, "recordCountIndex", 1, "controlTotalIndex", 2);
    assertThat(OutboundTrailerRecord.buildValues(tmpl, 42L, null)).containsExactly("T", "42", "");
  }

  @Test
  @DisplayName("显式 fieldCount 撑开更多列;recordType 默认 T")
  void buildValues_explicitFieldCount() {
    Map<String, Object> tmpl = Map.of("recordCountIndex", 1, "fieldCount", 5);
    assertThat(OutboundTrailerRecord.buildValues(tmpl, 7L, null))
        .containsExactly("T", "7", "", "", "");
  }
}
