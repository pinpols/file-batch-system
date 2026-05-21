package com.example.batch.worker.exports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Export 格式策略注册中心单测：
 *
 * <ol>
 *   <li>大小写不敏感解析
 *   <li>空白 / null / 未知格式 → resolve fallback 到 JSON
 *   <li>未注册格式 → require 抛 INVALID_ARGUMENT BizException
 * </ol>
 */
class ExportFormatStrategyRegistryTest {

  private static ExportFormatStrategy stub(String formatType) {
    return new ExportFormatStrategy() {
      @Override
      public String formatType() {
        return formatType;
      }

      @Override
      public long generate(ExportFormatContext ctx) {
        return 0L;
      }
    };
  }

  private ExportFormatStrategyRegistry newRegistry(ExportFormatStrategy... strategies) {
    return new ExportFormatStrategyRegistry(List.of(strategies));
  }

  @Test
  @DisplayName("resolve(): 精确大写匹配命中")
  void shouldReturnStrategy_whenFormatTypeMatchesUpperCase() {
    ExportFormatStrategy json = stub("JSON");
    ExportFormatStrategy delimited = stub("DELIMITED");
    ExportFormatStrategyRegistry registry = newRegistry(json, delimited);

    assertThat(registry.resolve("JSON")).isSameAs(json);
    assertThat(registry.resolve("DELIMITED")).isSameAs(delimited);
  }

  @Test
  @DisplayName("resolve(): 大小写不敏感 + trim 空白")
  void shouldNormalizeCase_andTrimWhitespace_whenResolving() {
    ExportFormatStrategy json = stub("JSON");
    ExportFormatStrategy excel = stub("excel");
    ExportFormatStrategyRegistry registry = newRegistry(json, excel);

    assertThat(registry.resolve("json")).isSameAs(json);
    assertThat(registry.resolve("ExCeL")).isSameAs(excel);
    assertThat(registry.resolve("  json  ")).isSameAs(json);
  }

  @Test
  @DisplayName("resolve(): null / 空白 → fallback 到 JSON")
  void shouldFallbackToJson_whenFormatTypeNullOrBlank() {
    ExportFormatStrategy json = stub("JSON");
    ExportFormatStrategy other = stub("CSV");
    ExportFormatStrategyRegistry registry = newRegistry(json, other);

    assertThat(registry.resolve(null)).isSameAs(json);
    assertThat(registry.resolve("")).isSameAs(json);
    assertThat(registry.resolve("   ")).isSameAs(json);
  }

  @Test
  @DisplayName("resolve(): 未知格式类型 → fallback 到 JSON")
  void shouldFallbackToJson_whenFormatTypeUnknown() {
    ExportFormatStrategy json = stub("JSON");
    ExportFormatStrategyRegistry registry = newRegistry(json);

    assertThat(registry.resolve("PARQUET")).isSameAs(json);
  }

  @Test
  @DisplayName("require(): 命中返回；未命中抛 INVALID_ARGUMENT BizException")
  void shouldThrowBizException_whenRequireUnknownFormat() {
    ExportFormatStrategy json = stub("JSON");
    ExportFormatStrategyRegistry registry = newRegistry(json);

    assertThat(registry.require("JSON")).isSameAs(json);
    assertThatThrownBy(() -> registry.require("PARQUET"))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
  }

  @Test
  @DisplayName("require(null) / require(空) → 抛 BizException（不 fallback）")
  void shouldThrowBizException_whenRequireNullOrBlank() {
    ExportFormatStrategy json = stub("JSON");
    ExportFormatStrategyRegistry registry = newRegistry(json);

    assertThatThrownBy(() -> registry.require(null)).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> registry.require("")).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> registry.require("   ")).isInstanceOf(BizException.class);
  }
}
