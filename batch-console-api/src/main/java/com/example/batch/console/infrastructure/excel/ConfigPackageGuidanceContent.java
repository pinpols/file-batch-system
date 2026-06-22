package com.example.batch.console.infrastructure.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置包 Excel 的「展示性内容」外置加载器：把 EXAMPLE_* 示例片段与 3 张只读说明 sheet（依赖说明 / 四类Worker示例 / 文件束示例）的表头与数据行 从
 * classpath 资源 {@code /config-package-guidance.json} 读入，避免在 {@link
 * ConfigPackageExcelWorkbookWriter} 内硬编码大段中文文案与示例 JSON。
 *
 * <p>sheet 行单元格里允许出现 {@code ${fragmentKey}} 占位符，加载时按 {@code fragments} 解析（一个单元格可含占位符子串，所有 {@code
 * ${key}} 出现处全部替换），从而消除示例片段在 sheet 行里的重复。fail-fast：资源缺失/解析失败/未知占位符一律抛 {@link
 * IllegalStateException}。
 */
public final class ConfigPackageGuidanceContent {

  private static final String RESOURCE = "/config-package-guidance.json";
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

  private final Map<String, String> fragments;
  private final Map<String, Sheet> sheets;

  private ConfigPackageGuidanceContent(Map<String, String> fragments, Map<String, Sheet> sheets) {
    this.fragments = fragments;
    this.sheets = sheets;
  }

  /** 不可变只读说明 sheet：表头 + 数据行（占位符已在加载期解析完毕）。 */
  public static final class Sheet {
    private final String[] headers;
    private final List<String[]> rows;

    private Sheet(String[] headers, List<String[]> rows) {
      this.headers = headers;
      this.rows = rows;
    }

    public String[] headers() {
      return headers.clone();
    }

    public List<String[]> rows() {
      return rows;
    }
  }

  /** 从 classpath 读取并解析 guidance 资源，解析所有 {@code ${key}} 占位符。fail-fast。 */
  public static ConfigPackageGuidanceContent load() {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root;
    try (InputStream in = ConfigPackageGuidanceContent.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("缺少配置包指南资源: " + RESOURCE);
      }
      root = mapper.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("无法解析配置包指南资源: " + RESOURCE, e);
    }

    JsonNode fragmentsNode = root.get("fragments");
    if (fragmentsNode == null || !fragmentsNode.isObject()) {
      throw new IllegalStateException(RESOURCE + " 缺少 fragments 对象");
    }
    Map<String, String> fragments = new LinkedHashMap<>();
    fragmentsNode.properties().forEach(e -> fragments.put(e.getKey(), e.getValue().asText()));

    JsonNode sheetsNode = root.get("sheets");
    if (sheetsNode == null || !sheetsNode.isObject()) {
      throw new IllegalStateException(RESOURCE + " 缺少 sheets 对象");
    }
    Map<String, Sheet> sheets = new LinkedHashMap<>();
    sheetsNode
        .properties()
        .forEach(e -> sheets.put(e.getKey(), parseSheet(e.getKey(), e.getValue(), fragments)));

    return new ConfigPackageGuidanceContent(fragments, sheets);
  }

  private static Sheet parseSheet(String name, JsonNode node, Map<String, String> fragments) {
    JsonNode headersNode = node.get("headers");
    if (headersNode == null || !headersNode.isArray()) {
      throw new IllegalStateException(RESOURCE + " sheet '" + name + "' 缺少 headers 数组");
    }
    String[] headers = new String[headersNode.size()];
    for (int i = 0; i < headersNode.size(); i++) {
      headers[i] = resolve(headersNode.get(i).asText(), fragments, name);
    }

    JsonNode rowsNode = node.get("rows");
    if (rowsNode == null || !rowsNode.isArray()) {
      throw new IllegalStateException(RESOURCE + " sheet '" + name + "' 缺少 rows 数组");
    }
    List<String[]> rows = new ArrayList<>(rowsNode.size());
    for (JsonNode rowNode : rowsNode) {
      String[] cells = new String[rowNode.size()];
      for (int c = 0; c < rowNode.size(); c++) {
        cells[c] = resolve(rowNode.get(c).asText(), fragments, name);
      }
      rows.add(cells);
    }
    return new Sheet(headers, List.copyOf(rows));
  }

  /** 把单元格内所有 {@code ${key}} 替换为对应 fragment；未知占位符抛异常。 */
  private static String resolve(String cell, Map<String, String> fragments, String sheetName) {
    Matcher m = PLACEHOLDER.matcher(cell);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String key = m.group(1);
      String value = fragments.get(key);
      if (value == null) {
        throw new IllegalStateException(
            RESOURCE + " sheet '" + sheetName + "' 引用未知 fragment 占位符: ${" + key + "}");
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(value));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** 取示例片段；未知 key 抛异常。 */
  public String fragment(String key) {
    String value = fragments.get(key);
    if (value == null) {
      throw new IllegalStateException(RESOURCE + " 缺少 fragment: " + key);
    }
    return value;
  }

  /** 取只读说明 sheet；未知 name 抛异常。 */
  public Sheet sheet(String name) {
    Sheet sheet = sheets.get(name);
    if (sheet == null) {
      throw new IllegalStateException(RESOURCE + " 缺少 sheet: " + name);
    }
    return sheet;
  }
}
