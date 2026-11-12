package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("单 sheet 导入单元格解析: 规范化后校验并累积问题, 不在解析阶段抛错")
class SingleSheetExcelCellParsersTest {

  private static final Set<String> MODES = Set.of("FIFO", "PRIORITY");

  @Test
  @DisplayName("必填文本缺失时记录问题并返回空, 超长时截断并记录问题")
  void shouldReportMissingOrTooLongRequiredText() {
    List<String> issues = new ArrayList<>();

    assertThat(SingleSheetExcelCellParsers.requireText(Map.of(), "code", 4, issues))
        .isNull();
    assertThat(
            SingleSheetExcelCellParsers.requireText(Map.of("code", " abcdef "), "code", 4, issues))
        .isEqualTo("abcd");
    assertThat(SingleSheetExcelCellParsers.requireText(Map.of("code", "ab"), "code", 4, issues))
        .isEqualTo("ab");

    assertThat(issues).containsExactly("code is required", "code too long (max 4)");
  }

  @Test
  @DisplayName("可选文本留空返回空, 超长时截断并记录问题")
  void shouldTruncateTooLongOptionalText() {
    List<String> issues = new ArrayList<>();

    assertThat(SingleSheetExcelCellParsers.optionalText(Map.of(), "note", 3, issues))
        .isNull();
    assertThat(SingleSheetExcelCellParsers.optionalText(Map.of("note", "abcd"), "note", 3, issues))
        .isEqualTo("abc");

    assertThat(issues).containsExactly("note too long (max 3)");
  }

  @Test
  @DisplayName("必填枚举转大写后校验取值范围, 缺失时只记录必填问题")
  void shouldValidateRequiredEnum() {
    List<String> issues = new ArrayList<>();

    assertThat(SingleSheetExcelCellParsers.requireEnum(
            Map.of("mode", "fifo"), "mode", MODES, 16, issues))
        .isEqualTo("FIFO");
    assertThat(SingleSheetExcelCellParsers.requireEnum(Map.of(), "mode", MODES, 16, issues))
        .isNull();
    assertThat(SingleSheetExcelCellParsers.requireEnum(
            Map.of("mode", "lifo"), "mode", MODES, 16, issues))
        .isEqualTo("LIFO");

    assertThat(issues).containsExactly("mode is required", "mode must be one of " + MODES);
  }

  @Test
  @DisplayName("可选枚举留空回退默认值, 超长截断后仍校验取值范围")
  void shouldDefaultOrValidateOptionalEnum() {
    List<String> issues = new ArrayList<>();

    assertThat(SingleSheetExcelCellParsers.optionalEnum(Map.of(), "mode", MODES, 8, "FIFO", issues))
        .isEqualTo("FIFO");
    assertThat(SingleSheetExcelCellParsers.optionalEnum(
            Map.of("mode", "priority"), "mode", MODES, 8, "FIFO", issues))
        .isEqualTo("PRIORITY");
    assertThat(SingleSheetExcelCellParsers.optionalEnum(
            Map.of("mode", "roundrobin"), "mode", MODES, 5, "FIFO", issues))
        .isEqualTo("ROUND");

    assertThat(issues).containsExactly("mode too long (max 5)", "mode must be one of " + MODES);
  }

  @Test
  @DisplayName("整数解析: 可选留空回退默认值, 必填缺失回退下限, 非整数与低于下限都记录问题")
  void shouldParseIntegersWithFallbacks() {
    List<String> issues = new ArrayList<>();

    assertThat(SingleSheetExcelCellParsers.optionalInteger(Map.of(), "qps", 1, 10, issues))
        .isEqualTo(10);
    assertThat(
            SingleSheetExcelCellParsers.optionalInteger(Map.of("qps", "0"), "qps", 1, 10, issues))
        .isZero();
    assertThat(
            SingleSheetExcelCellParsers.optionalInteger(Map.of("qps", "x"), "qps", 1, 10, issues))
        .isEqualTo(10);
    assertThat(SingleSheetExcelCellParsers.requireInteger(Map.of(), "size", 2, issues))
        .isEqualTo(2);
    assertThat(SingleSheetExcelCellParsers.requireInteger(Map.of("size", "1"), "size", 2, issues))
        .isEqualTo(1);
    assertThat(SingleSheetExcelCellParsers.requireInteger(Map.of("size", "y"), "size", 2, issues))
        .isEqualTo(2);
    assertThat(SingleSheetExcelCellParsers.requireInteger(Map.of("size", "5"), "size", 2, issues))
        .isEqualTo(5);

    assertThat(issues)
        .containsExactly(
            "qps must be >= 1",
            "qps must be integer",
            "size is required",
            "size must be >= 2",
            "size must be integer");
  }

  @Test
  @DisplayName("布尔值接受 TRUE/Y/1/YES 与 FALSE/N/0/NO, 其他取值回退默认值并记录问题")
  void shouldParseBooleanAliases() {
    List<String> issues = new ArrayList<>();

    assertThat(SingleSheetExcelCellParsers.optionalBoolean(Map.of(), "on", true, issues))
        .isTrue();
    assertThat(
            SingleSheetExcelCellParsers.optionalBoolean(Map.of("on", "yes"), "on", false, issues))
        .isTrue();
    assertThat(SingleSheetExcelCellParsers.optionalBoolean(Map.of("on", "n"), "on", true, issues))
        .isFalse();
    assertThat(
            SingleSheetExcelCellParsers.optionalBoolean(Map.of("on", "maybe"), "on", true, issues))
        .isTrue();

    assertThat(issues).containsExactly("on must be boolean");
  }

  @Test
  @DisplayName("租户列留空沿用当前租户, 与当前租户不同时记录问题")
  void shouldResolveTenantField() {
    List<String> issues = new ArrayList<>();

    assertThat(SingleSheetExcelCellParsers.resolveTenantField(Map.of(), "ta", issues))
        .isEqualTo("ta");
    assertThat(
            SingleSheetExcelCellParsers.resolveTenantField(Map.of("tenant_id", "ta"), "ta", issues))
        .isEqualTo("ta");
    assertThat(
            SingleSheetExcelCellParsers.resolveTenantField(Map.of("tenant_id", "tb"), "ta", issues))
        .isEqualTo("tb");

    assertThat(issues).containsExactly("tenant_id must match current tenant: ta");
  }

  @Test
  @DisplayName("可选 JSON 留空返回空, 非法 JSON 保留原文并记录问题")
  void shouldValidateOptionalJson() {
    List<String> issues = new ArrayList<>();

    assertThat(SingleSheetExcelCellParsers.optionalJson(Map.of(), "cfg", issues))
        .isNull();
    assertThat(SingleSheetExcelCellParsers.optionalJson(Map.of("cfg", "{\"a\":1}"), "cfg", issues))
        .isEqualTo("{\"a\":1}");
    assertThat(SingleSheetExcelCellParsers.optionalJson(Map.of("cfg", "{bad"), "cfg", issues))
        .isEqualTo("{bad");

    assertThat(issues).containsExactly("cfg must be valid JSON");
  }
}
