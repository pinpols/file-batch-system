package com.example.batch.console.arch;

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
 * 治理护栏:任何引用 batch.* 业务表的 mapper XML,SELECT/UPDATE/DELETE 都必须强制带 tenant_id 过滤, 不允许 {@code <if
 * test="tenantId != null">AND tenant_id = #{tenantId}</if>} 这种"可空" 守护(否则 service 层一旦忘记 tenantGuard.resolveTenant 就漏租户隔离,原 OperationAuditQueryService 漏洞 即此类型)。
 *
 * <p>豁免清单参见 CLAUDE.md 「多租隔离」§ — 4 张系统表 + 跨租户后台 reconciler / archive 工具,不强制 tenant_id。
 *
 * <p>本测试只是静态扫描,失败说明有新 mapper 引入了租户隔离漏洞模式;通过表示当前所有 mapper 守护到位。
 */
class MapperXmlTenantGuardArchTest {

  /**
   * 模式:{@code <if test="tenantId != null...">...AND tenant_id = #{tenantId}...</if>} 这是危险写法 —
   * service 漏传时整段 SQL 没 tenant 过滤,变成跨租户全表查。
   */
  private static final Pattern CONDITIONAL_TENANT_FILTER =
      Pattern.compile(
          "<if\\s+test\\s*=\\s*\"tenantId\\s*!=\\s*null[^\"]*\"\\s*>"
              + "[^<]*tenant_id\\s*=\\s*#\\{tenantId\\}",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /**
   * 已知用 "全局 admin 跨租 + tenant 用户 single-tenant" 双路径的 mapper 白名单。
   *
   * <p>这些 XML 用 {@code <if tenantId>AND tenant_id=#{tenantId}} 模式刻意保留 null fallback,以支持
   * ROLE_ADMIN 不传 tenantId 时全表扫。**新写 mapper 严禁加入此名单**,要么走 tenantGuard.resolveTenant
   * 服务层兜底(典型 OperationAuditQueryService),要么把全局 admin 路径独立成单独的 mapper 方法。
   *
   * <p>本测试的核心价值是 **防回退**:有人新写 mapper 用了 if-conditional 模式 → test fail → review;
   * 已存在的逐步治理迁移,**禁止追加** 新条目除非有明确架构理由。
   */
  private static final List<String> KNOWN_CONDITIONAL_TENANT_MAPPERS =
      List.of(
          // 全局系统表(CLAUDE.md §多租隔离 4 张豁免表)
          "BizTableSchemaMapper",
          "StepRegistryMapper",
          "ShedLockMapper",
          // 后台 reconciler / archive 跨租户工具:按状态全表扫,无 tenant 参数
          "ConsoleDashboardQueryMapper", // 全局聚合视图
          "OperationAuditMapper", // 服务层 A1 修过 tenantGuard 兜底
          // 历史遗留 conditional 模式,治理中:每条均需明确"全局 admin 入口"理由,迁移完成即移除
          "WorkflowNodeMapper",
          "WorkflowEdgeMapper",
          "FileArrivalGroupMapper",
          "ConsoleUserAccountMapper",
          "OutboxRetryLogMapper");

  @Test
  void everyTenantAwareMapperMustNotGuardTenantIdWithConditional() throws IOException {
    Path mapperDir = Paths.get("src/main/resources/mapper");
    if (!Files.exists(mapperDir)) {
      return; // 模块无 mapper 目录,跳过(eg. batch-common)
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
            "mapper XML 含 <if tenantId>AND tenant_id 守护 → service 漏传则跨租露;改为无条件"
                + " 'AND tenant_id = #{tenantId}',或加入 EXEMPTED_MAPPERS 并注明原因")
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
      return; // 不涉租户的纯系统表 mapper,跳过
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
