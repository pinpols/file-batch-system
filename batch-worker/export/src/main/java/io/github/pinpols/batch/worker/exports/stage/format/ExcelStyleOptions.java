package io.github.pinpols.batch.worker.exports.stage.format;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Excel 表头样式与分组合并表头的<b>可选</b>声明,从 {@code template_config.header_style} / {@code
 * template_config.header_groups} 解析。全部未配置时 {@link #styled()} 为 {@code false},导出走历史「裸值无样式」路径。
 *
 * <p>解析逻辑复用 {@link AbstractExportFormat} 的 {@code toMap / firstNonNull / integerValue / textValue}
 * 工具方法,避免重复实现,也不污染 AbstractExportFormat 的公共 API。
 */
record ExcelStyleOptions(
    boolean bold,
    byte[] backgroundRgb,
    boolean freezeHeader,
    boolean autoWidth,
    List<HeaderGroup> headerGroups) {

  /** 分组合并表头:{@code [from, to]} 闭区间列下标 + 分组标题,合并仅作用于第 0 行(数据行之上)。 */
  record HeaderGroup(String title, int fromColumn, int toColumn) {}

  static final ExcelStyleOptions NONE = new ExcelStyleOptions(false, null, false, false, List.of());

  boolean styled() {
    return bold || backgroundRgb != null || freezeHeader || autoWidth || !headerGroups.isEmpty();
  }

  boolean hasHeaderGroups() {
    return !headerGroups.isEmpty();
  }

  static ExcelStyleOptions from(Map<String, Object> templateConfig, AbstractExportFormat helper) {
    if (templateConfig == null || templateConfig.isEmpty()) {
      return NONE;
    }
    Map<String, Object> style =
        helper.toMap(
            helper.firstNonNull(
                templateConfig.get("header_style"), templateConfig.get("headerStyle")));
    boolean bold = booleanValue(style.get("bold"));
    byte[] background =
        parseRgb(helper.textValue(helper.firstNonNull(style.get("background"), style.get("bg"))));
    boolean freeze =
        booleanValue(helper.firstNonNull(style.get("freeze_header"), style.get("freezeHeader")));
    boolean autoWidth =
        booleanValue(helper.firstNonNull(style.get("auto_width"), style.get("autoWidth")));
    List<HeaderGroup> groups =
        parseGroups(
            helper.firstNonNull(
                templateConfig.get("header_groups"), templateConfig.get("headerGroups")),
            helper);
    ExcelStyleOptions options = new ExcelStyleOptions(bold, background, freeze, autoWidth, groups);
    return options.styled() ? options : NONE;
  }

  private static List<HeaderGroup> parseGroups(Object raw, AbstractExportFormat helper) {
    if (!(raw instanceof Collection<?> list)) {
      return List.of();
    }
    List<HeaderGroup> groups = new ArrayList<>();
    for (Object item : list) {
      Map<String, Object> map = helper.toMap(item);
      if (map.isEmpty()) {
        continue;
      }
      Integer from =
          helper.integerValue(helper.firstNonNull(map.get("from"), map.get("fromColumn")));
      Integer to = helper.integerValue(helper.firstNonNull(map.get("to"), map.get("toColumn")));
      String title = helper.textValue(helper.firstNonNull(map.get("title"), map.get("label")));
      if (from == null || to == null || to < from) {
        continue;
      }
      groups.add(new HeaderGroup(title == null ? "" : title, from, to));
    }
    return List.copyOf(groups);
  }

  /** 解析 {@code #RRGGBB} / {@code RRGGBB} 6 位十六进制为 3 字节 RGB;非法 / 缺失返回 null(不上底色)。 */
  private static byte[] parseRgb(String value) {
    if (value == null) {
      return null;
    }
    String hex = value.startsWith("#") ? value.substring(1) : value;
    if (hex.length() != 6) {
      return null;
    }
    try {
      int rgb = Integer.parseInt(hex, 16);
      return new byte[] {
        (byte) ((rgb >> 16) & 0xFF), (byte) ((rgb >> 8) & 0xFF), (byte) (rgb & 0xFF)
      };
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static boolean booleanValue(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
  }
}
