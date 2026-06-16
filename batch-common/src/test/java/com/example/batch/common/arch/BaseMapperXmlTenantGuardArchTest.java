package com.example.batch.common.arch;

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
 * 跨模块复用基类:扫描 {@code src/main/resources/mapper/**.xml},禁止 {@code <if test="tenantId != null">AND
 * tenant_id = #{tenantId}</if>} 这种可空租户过滤。子类可 override {@link #knownConditionalTenantMappers()}
 * 注册各自模块允许的 ROLE_ADMIN 跨租入口白名单。
 *
 * <p>新模块加守护时只需 {@code class XxxMapperXmlTenantGuardArchTest extends
 * BaseMapperXmlTenantGuardArchTest}, 必要时 override 白名单方法即可。详见 CLAUDE.md §多租隔离。
 *
 * <p><b>⚠️ 守护边界(2026-06-16 审计澄清):本测只做"防回退的文件名白名单",不验证运行时隔离。</b>白名单里的 mapper(含用户可达的
 * WorkflowNode/FileArrivalGroup/OperationAudit 等)其可空 {@code <if tenantId>} 方法, 安全**完全依赖调用方先经 {@code
 * resolveTenant}/{@code ConsoleTenantGuard} 拿到非空 tenantId**——租户角色下
 * 该值恒非空,故当前调用链安全。但本测**无法**保证未来新增/重构的调用方不会直接传 {@code request.getTenantId()} (可为 null →
 * 全租户扫描)且**测试不会因此变红**(mapper 已在白名单)。**红线**:这些 mapper 的可空-tenant 方法 只允许从已校验 global-role
 * 的服务调用;新写调用方必须走 {@code resolveTenant},不得裸传可空 tenantId。 真正的隔离靠运行时纪律,不靠本静态白名单。
 */
public abstract class BaseMapperXmlTenantGuardArchTest {

  private static final Pattern CONDITIONAL_TENANT_FILTER =
      Pattern.compile(
          "<if\\s+test\\s*=\\s*\"tenantId\\s*!=\\s*null[^\"]*\"\\s*>"
              + "[^<]*tenant_id\\s*=\\s*#\\{tenantId\\}",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 子类 override 注册本模块允许的 ROLE_ADMIN 跨租 mapper 白名单(按 mapper 文件名前缀匹配)。默认空。 */
  protected List<String> knownConditionalTenantMappers() {
    return List.of();
  }

  @Test
  void everyTenantAwareMapperMustNotGuardTenantIdWithConditional() throws IOException {
    Path mapperDir = Paths.get("src/main/resources/mapper");
    if (!Files.exists(mapperDir)) {
      return;
    }
    List<String> whitelist = knownConditionalTenantMappers();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mapperDir)) {
      files
          .filter(p -> p.toString().endsWith(".xml"))
          .filter(p -> !isExempted(p, whitelist))
          .forEach(p -> scan(p, violations));
    }
    assertThat(violations)
        .as(
            "mapper XML 含 <if tenantId>AND tenant_id 守护 — 改为无条件,或加入子类"
                + " knownConditionalTenantMappers() 并注明 ROLE_ADMIN 跨租理由")
        .isEmpty();
  }

  private static boolean isExempted(Path file, List<String> whitelist) {
    String name = file.getFileName().toString();
    return whitelist.stream().anyMatch(name::startsWith);
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
