package com.example.batch.orchestrator.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 治理护栏:batch-orchestrator mapper XML 中 {@code <if test="tenantId != null">AND tenant_id =
 * #{tenantId}</if>} 这种"可空"租户过滤,只允许在已知的 ROLE_ADMIN 跨租运维入口存在。新增 mapper 必须走无条件 {@code AND tenant_id =
 * #{tenantId}} 或加入白名单并写明原因。
 *
 * <p>与 batch-console-api 同名 ArchUnit test 是姊妹守护,各扫各模块的 mapper XML。
 */
class MapperXmlTenantGuardArchTest {

  private static final Pattern CONDITIONAL_TENANT_FILTER =
      Pattern.compile(
          "<if\\s+test\\s*=\\s*\"tenantId\\s*!=\\s*null[^\"]*\"\\s*>"
              + "[^<]*tenant_id\\s*=\\s*#\\{tenantId\\}",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /**
   * 已知 ROLE_ADMIN 跨租运维查询 / dashboard 聚合的 mapper 白名单。新写 mapper **严禁追加** — 应改成无条件租户过滤(典型业务路径)或拆出独立的
   * admin 全局方法。
   */
  private static final List<String> KNOWN_CONDITIONAL_TENANT_MAPPERS =
      List.of(
          // 跨租 retry / outbox 重试观察台 — admin 全局查
          "EventDeliveryLogMapper",
          "EventOutboxRetryMapper",
          "RetryScheduleMapper",
          "OutboxEventMapper",
          // 跨租 batch-day 等待视图 — admin 全局排查
          "BatchDayWaitingLaunchMapper",
          // 跨租文件治理聚合
          "FileGovernanceMapper");

  @Test
  void everyTenantAwareMapperMustNotGuardTenantIdWithConditional() throws IOException {
    Path mapperDir = Paths.get("src/main/resources/mapper");
    if (!Files.exists(mapperDir)) {
      return;
    }
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mapperDir)) {
      files
          .filter(p -> p.toString().endsWith(".xml"))
          .filter(p -> !isExempted(p))
          .forEach(p -> scan(p, violations));
    }
    assertThat(violations)
        .as(
            "orchestrator mapper XML 含 <if tenantId>AND tenant_id 守护 — 改为无条件,或加入"
                + " KNOWN_CONDITIONAL_TENANT_MAPPERS 并注明 ROLE_ADMIN 跨租理由")
        .isEmpty();
  }

  private static boolean isExempted(Path file) {
    String name = file.getFileName().toString();
    return KNOWN_CONDITIONAL_TENANT_MAPPERS.stream().anyMatch(name::startsWith);
  }

  private static void scan(Path xml, List<String> violations) {
    String content;
    try {
      content = Files.readString(xml);
    } catch (IOException ex) {
      violations.add(xml + " — read failed: " + ex.getMessage());
      return;
    }
    if (!content.contains("tenant_id")) {
      return;
    }
    Matcher m = CONDITIONAL_TENANT_FILTER.matcher(content);
    while (m.find()) {
      int lineNo = lineOf(content, m.start());
      violations.add(xml.getFileName() + ":" + lineNo + " — " + truncate(m.group(), 80));
    }
  }

  private static int lineOf(String s, int offset) {
    int line = 1;
    for (int i = 0; i < offset && i < s.length(); i++) {
      if (s.charAt(i) == '\n') line++;
    }
    return line;
  }

  private static String truncate(String s, int max) {
    String flat = s.replaceAll("\\s+", " ");
    return flat.length() <= max ? flat : flat.substring(0, max) + "...";
  }
}
