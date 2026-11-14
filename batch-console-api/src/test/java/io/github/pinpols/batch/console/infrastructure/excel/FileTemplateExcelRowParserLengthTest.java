package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件模板行解析: 安全字段长度按数据库列宽校验")
class FileTemplateExcelRowParserLengthTest {

  @Test
  @DisplayName("masking_rule_set 列宽 128: 128 字符通过, 129 字符被拒绝")
  void shouldEnforceMaskingRuleSetColumnWidth() {
    List<String> allowedIssues = new ArrayList<>();
    FileTemplateExcelRowParser.parseRow(
        "ta", 1, templateRow("masking_rule_set", "m".repeat(128)), allowedIssues);
    assertThat(allowedIssues)
        .as("128 字符等于列宽, 不应报超长")
        .noneMatch(issue -> issue.startsWith("masking_rule_set too long"));

    List<String> rejectedIssues = new ArrayList<>();
    FileTemplateExcelRowParser.parseRow(
        "ta", 1, templateRow("masking_rule_set", "m".repeat(129)), rejectedIssues);
    assertThat(rejectedIssues)
        .as("129 字符超过 VARCHAR(128), 解析期应拒绝")
        .anyMatch(issue -> issue.startsWith("masking_rule_set too long"));
  }

  @Test
  @DisplayName("encryption_key_ref 列宽 256: 256 字符通过, 257 字符被拒绝")
  void shouldEnforceEncryptionKeyRefColumnWidth() {
    List<String> allowedIssues = new ArrayList<>();
    FileTemplateExcelRowParser.parseRow(
        "ta", 1, templateRow("encryption_key_ref", "k".repeat(256)), allowedIssues);
    assertThat(allowedIssues)
        .as("256 字符等于列宽, 不应报超长")
        .noneMatch(issue -> issue.startsWith("encryption_key_ref too long"));

    List<String> rejectedIssues = new ArrayList<>();
    FileTemplateExcelRowParser.parseRow(
        "ta", 1, templateRow("encryption_key_ref", "k".repeat(257)), rejectedIssues);
    assertThat(rejectedIssues)
        .as("257 字符超过 VARCHAR(256), 解析期应拒绝")
        .anyMatch(issue -> issue.startsWith("encryption_key_ref too long"));
  }

  private static Map<String, String> templateRow(String key, String value) {
    return Map.of("template_code", "tpl", key, value);
  }
}
