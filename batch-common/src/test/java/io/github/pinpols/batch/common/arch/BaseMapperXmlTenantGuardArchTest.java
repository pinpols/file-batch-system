package io.github.pinpols.batch.common.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

  /** 每个 {@code <update>} / {@code <delete>} 语句块(含属性 + body)。 */
  private static final Pattern WRITE_STATEMENT_BLOCK =
      Pattern.compile(
          "<(update|delete)\\b([^>]*)>(.*?)</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 语句块内首个 {@code UPDATE batch.<t>} / {@code DELETE FROM batch.<t>} 目标表(捕获表名)。 */
  private static final Pattern BATCH_WRITE_TARGET =
      Pattern.compile("\\b(?:update|delete\\s+from)\\s+batch\\.(\\w+)", Pattern.CASE_INSENSITIVE);

  /** WHERE 子句起点:静态 {@code WHERE} 或动态 {@code <where>}。 */
  private static final Pattern WHERE_START =
      Pattern.compile("<where\\b|\\bwhere\\b", Pattern.CASE_INSENSITIVE);

  /** 语句 id 属性。 */
  private static final Pattern STATEMENT_ID =
      Pattern.compile("id\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

  /** 子类 override 注册本模块允许的 ROLE_ADMIN 跨租 mapper 白名单(按 mapper 文件名前缀匹配)。默认空。 */
  protected List<String> knownConditionalTenantMappers() {
    return List.of();
  }

  /**
   * <b>表级豁免</b>:这些 {@code batch.*} 表**物理上没有 {@code tenant_id} 列**,按 CLAUDE.md §多租隔离合法豁免两类——① 系统表;②
   * run/config 明细子表(仅经父表 FK + 父 id 访问,父行已按 tenant 校验)。对这些表的 UPDATE/DELETE 无法也不应带 tenant_id 谓词。
   *
   * <p>基类给出全仓通用集合(跨模块一致的系统表 + 已知无 tenant_id 列的子表);子类可 override 并 {@code union} 追加本模块特有的无 tenant_id
   * 列表,但**每追加一张都必须在此处注明"系统表 / run 子表 / 其他 by-design"及无 tenant_id 列的依据**。
   */
  protected Set<String> tenantExemptTables() {
    return Set.of(
        // ① 系统表(CLAUDE.md §多租隔离 4 张豁免 + 全局目录),无 tenant_id 列
        "shedlock", // 分布式锁,全局系统表
        "step_registry", // 步骤注册表,全局系统表
        "biz_table_schema", // 业务库 schema 元数据,全局系统表
        "batch_runtime_default_parameter", // 运行时默认参数,全局系统表
        "business_shard_catalog", // 租户→分片路由目录,全局主数据(跨租,无 tenant_id 列)
        // ② run/config 明细子表:无 tenant_id 列(逐个据 DDL 核实),靠父表 FK + 父 id 隔离(父行按 tenant 校验)
        "pipeline_step_definition", // V6:pipeline_definition 的配置子表,经 pipeline_definition_id 访问
        "pipeline_step_run", // V6:pipeline_instance 的 run 子表,经 pipeline_instance_id 访问
        "workflow_node_run"); // V5:workflow_run 的 run 子表,经 workflow_run_id 访问
    // 注:file_record / file_dispatch_record **有** tenant_id 列(V6 tenant_id NOT NULL)——它们是租户可达的
    // 文件治理/派单表,不放这里(否则整表失明);其确属 by-design 的具体语句走 knownTenantlessBatchWriteStatements()。
  }

  /**
   * <b>语句级豁免</b>:表**带 {@code tenant_id} 列**,但该 UPDATE/DELETE 的租户隔离**不在本条 SQL 的
   * WHERE**——而在:内部后台/调度/发件箱按状态或全局 id 扫描(跨租 by-design,加 tenant 谓词反而会破坏)、worker 按已认领 run id
   * 执行、归档级联按预选实例 id 集删除、或服务层已 {@code assertSameTenant / resolveTenant} 读校验后再按 id 写。key 形如 {@code
   * "MapperFileName#statementId"}。
   *
   * <p><b>红线:新写业务/用户可达的 batch.* UPDATE/DELETE 一律要带 tenant_id
   * 谓词,严禁往此名单追加以图省事。</b>追加前必须逐条核实隔离机制并注明理由;这正是补此检测要堵的 T4(NotificationDeliveryLog.updateStatus 按 id
   * 改、无 tenant 谓词)那类 IDOR 盲区。
   */
  protected Set<String> knownTenantlessBatchWriteStatements() {
    return Set.of();
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

  /**
   * <b>补 {@code MapperXmlTenantGuardArchTest} 盲区(T4 #779)</b>:原检测只匹配 {@code <if tenantId>AND
   * tenant_id} 这一种可空守护,且 {@code if(!content.contains("tenant_id")) return} 短路——对"完全不带 tenant 子句、只按
   * id 改/删"的 batch.* 写(潜伏 IDOR)**根本不报**。
   *
   * <p>本检测:扫 {@code <update>}/{@code <delete>},凡目标表是 {@code batch.<t>} 且不在 {@link
   * #tenantExemptTables()} 的,断言其 WHERE 子句出现 {@code tenant_id}。缺失即违规——要么补 {@code AND tenant_id =
   * #{...}},要么(确属 by-design)加入 {@link #tenantExemptTables()} 或 {@link
   * #knownTenantlessBatchWriteStatements()} 并注明理由。
   *
   * <p><b>检测软肋(已知假阴性面):</b>只要 WHERE 起点之后**任意位置**出现 {@code tenant_id} 字面即判通过——包括它只出现在 子查询 / EXISTS /
   * IN 里、并不约束被改行本身(如 {@code WHERE id = #{id} AND x IN (SELECT ... WHERE tenant_id =
   * ...)})。这类"tenant_id 只约束子查询"的写会漏过。存量已逐条人工核实无此形态;此为静态字面扫描的固有局限,真隔离仍靠 code review + 运行时纪律兜底。
   */
  @Test
  void everyBatchTableWriteMustFilterByTenantId() throws IOException {
    Path mapperDir = Paths.get("src/main/resources/mapper");
    if (!Files.exists(mapperDir)) {
      return;
    }
    Set<String> exemptTables = tenantExemptTables();
    Set<String> exemptStatements = knownTenantlessBatchWriteStatements();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mapperDir)) {
      files
          .filter(p -> p.toString().endsWith(".xml"))
          .forEach(p -> scanBatchWrites(p, exemptTables, exemptStatements, violations));
    }
    assertThat(violations)
        .as(
            "batch.* 表的 UPDATE/DELETE 缺 tenant_id 谓词(WHERE 无 tenant_id)——潜在跨租 IDOR。"
                + "修正:WHERE 补 AND tenant_id = #{...};若确属 by-design(系统表/run 子表无 tenant_id 列 →"
                + " tenantExemptTables();内部后台/调度/发件箱/worker/归档/服务层已校验 →"
                + " knownTenantlessBatchWriteStatements())须加白名单并注明理由")
        .isEmpty();
  }

  private void scanBatchWrites(
      Path xml, Set<String> exemptTables, Set<String> exemptStatements, List<String> violations) {
    String content;
    try {
      content = Files.readString(xml);
    } catch (IOException ex) {
      violations.add(xml + " — read failed: " + ex.getMessage());
      return;
    }
    String mapperName = xml.getFileName().toString().replaceFirst("\\.xml$", "");
    Matcher block = WRITE_STATEMENT_BLOCK.matcher(content);
    while (block.find()) {
      String kind = block.group(1).toLowerCase(Locale.ROOT);
      String attrs = block.group(2);
      String body = block.group(3);
      Matcher target = BATCH_WRITE_TARGET.matcher(body);
      if (!target.find()) {
        continue; // 非 batch.* 目标表(sqlite outbox / biz.* / 其他库)不在本检测范围
      }
      String table = target.group(1).toLowerCase(Locale.ROOT);
      if (exemptTables.contains(table)) {
        continue; // 无 tenant_id 列的系统表 / run 子表
      }
      Matcher idm = STATEMENT_ID.matcher(attrs);
      String statementId = idm.find() ? idm.group(1) : "?";
      String key = mapperName + "#" + statementId;
      if (exemptStatements.contains(key)) {
        continue; // by-design 隔离不在本条 SQL 的 WHERE(已注明理由)
      }
      Matcher where = WHERE_START.matcher(body);
      String whereRegion = where.find(target.end()) ? body.substring(where.start()) : "";
      if (!whereRegion.toLowerCase(Locale.ROOT).contains("tenant_id")) {
        int lineNo = lineOf(content, block.start());
        violations.add(
            xml.getFileName()
                + ":"
                + lineNo
                + " ["
                + table
                + "] "
                + key
                + " — batch.* "
                + kind
                + (whereRegion.isEmpty() ? " 无 WHERE 子句(全表写)" : " 的 WHERE 缺 tenant_id 谓词"));
      }
    }
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
