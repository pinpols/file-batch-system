package com.example.batch.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 守护:{@code messages.properties}(en)与 {@code messages_zh_CN.properties}(zh)的 key 必须 1:1 对齐。
 *
 * <p>CLAUDE.md §字典/i18n + docs/design/i18n.md:两个文件 key 集合必须完全一致。否则新增业务异常 key 时漏补某一侧, 对应 locale
 * 用户会静默降级(zh 缺 → 回退英文 / ResultCode.label()),且无任何 CI 反馈。此前仅靠人工评审,本测试补上自动守护。
 *
 * <p>同时校验:① 无重复 key(Properties 会静默后者覆盖前者);② value 非空(空翻译等于没翻译)。
 */
class MessagesPropertiesParityTest {

  private static final String EN = "/messages.properties";
  private static final String ZH = "/messages_zh_CN.properties";

  @Test
  void enAndZhKeysAreOneToOne() throws IOException {
    Set<String> enKeys = loadKeys(EN);
    Set<String> zhKeys = loadKeys(ZH);

    Set<String> onlyInEn = new TreeSet<>(enKeys);
    onlyInEn.removeAll(zhKeys);
    Set<String> onlyInZh = new TreeSet<>(zhKeys);
    onlyInZh.removeAll(enKeys);

    assertThat(onlyInEn)
        .as("messages.properties(en) 有、messages_zh_CN.properties(zh) 缺的 key —— 请补 zh 翻译")
        .isEmpty();
    assertThat(onlyInZh)
        .as("messages_zh_CN.properties(zh) 有、messages.properties(en) 缺的 key —— 请补 en 翻译")
        .isEmpty();
  }

  @Test
  void noDuplicateKeysAndNoBlankValues() throws IOException {
    assertNoDuplicateKeysAndNoBlankValues(EN);
    assertNoDuplicateKeysAndNoBlankValues(ZH);
  }

  private static Set<String> loadKeys(String resource) throws IOException {
    Properties props = new Properties();
    try (InputStream in = MessagesPropertiesParityTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("缺少资源文件: %s", resource).isNotNull();
      props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    return new TreeSet<>(props.stringPropertyNames());
  }

  /** Properties.load 对重复 key 静默用后者覆盖前者 —— 手工逐行解析才能发现重复。 */
  private static void assertNoDuplicateKeysAndNoBlankValues(String resource) throws IOException {
    Set<String> seen = new LinkedHashSet<>();
    Set<String> duplicates = new TreeSet<>();
    Set<String> blankValues = new TreeSet<>();
    try (InputStream in = MessagesPropertiesParityTest.class.getResourceAsStream(resource);
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
          continue;
        }
        int eq = trimmed.indexOf('=');
        if (eq < 0) {
          continue;
        }
        String key = trimmed.substring(0, eq).strip();
        String value = trimmed.substring(eq + 1).strip();
        if (!seen.add(key)) {
          duplicates.add(key);
        }
        // {@code *.readme.line*} 是 Excel 包 README 模板的逐行 key,空 value 表示模板里的空行,合法豁免。
        if (value.isEmpty() && !key.contains(".readme.line")) {
          blankValues.add(key);
        }
      }
    }
    assertThat(duplicates).as("%s 存在重复 key(Properties 会静默覆盖)", resource).isEmpty();
    assertThat(blankValues).as("%s 存在空 value(空翻译等于漏译)", resource).isEmpty();
  }
}
