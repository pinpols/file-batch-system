package io.github.pinpols.batch.common.rls;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Phase A · RLS 闭世界守护(closed-world)。
 *
 * <p><b>动机</b>:旧的健康检查遍历硬编码 {@code EXPECTED_RLS_TABLES} 清单。新增 biz 表漏配 RLS 时,因为不在清单里,health 仍报绿 →
 * 静默跨租户泄露。本检查器改为<b>扫真实 biz schema</b>:任何缺 RLS(ENABLE/FORCE)或缺 policy 的 biz 表都会被发现,新增表漏配不再无感。
 *
 * <p><b>分区子表</b>:biz 有若干分区父表(如 {@code customer_account})及其分区子表(如 {@code customer_account_p0})。RLS
 * 加在父表、子表通过分区继承生效。扫描用 {@code relispartition = false} 排除子表,{@code relkind IN ('r','p')} 只取普通表 +
 * 分区父表,避免把子表当成「缺 policy」误报。
 *
 * <p><b>豁免</b>:{@code exemptBizTables} 默认空 —— biz 目前全是业务表都要 RLS。仅当新增的是<b>非租户的 biz 元数据表</b>时,才把表名(不带
 * schema 前缀)加进豁免清单。
 *
 * <p>{@code batch.process_staging} 在 batch schema(不在 biz),单独以固定全名检查。
 *
 * <p>本类无状态、线程安全,被 health indicator 与启动期 fail-fast 守护共享,避免逻辑复制。
 */
@Slf4j
public class RlsClosedWorldChecker {

  /** biz schema 名。RLS 仅覆盖 biz.* + batch.process_staging。 */
  public static final String BIZ_SCHEMA = "biz";

  /** batch schema 下唯一受 RLS 保护的表(带 tenant_id 的瞬态暂存表),单独全名检查。 */
  public static final String PROCESS_STAGING_SCHEMA = "batch";

  public static final String PROCESS_STAGING_TABLE = "process_staging";

  /** 翻转 transition → strict 期间,健康检查接受任一 policy 名(灰度兼容)。 */
  public static final List<String> ACCEPTED_POLICY_NAMES =
      List.of("tenant_isolation_transition", "tenant_isolation_strict");

  /**
   * <b>仅作参考 / 静态守护用</b>,运行期闭世界检查<b>不</b>读它 —— 闭世界直接扫真实 biz schema。保留它只为 {@code
   * RlsPhaseAMigrationCoverageTest} 校验 rls-phase-a*.sql 三脚本相互一致(列出同一组表)。新增 biz
   * 表无需更新本清单(闭世界自动覆盖),但若往 rls-phase-a 脚本加表,仍应同步更新此参考以保持脚本守护有效。
   */
  public static final List<String> REFERENCE_RLS_TABLES =
      List.of(
          "biz.customer_account",
          "biz.process_account_summary",
          "biz.process_event_copy",
          "biz.process_order_event",
          "biz.risk_alert",
          "biz.risk_score",
          "biz.settlement_batch",
          "biz.settlement_detail",
          "biz.transaction",
          "batch.process_staging");

  /**
   * 闭世界扫真实 biz 表:只取普通表('r')+ 分区父表('p'),用 {@code relispartition=false} 排除分区子表(RLS 加父表、子表继承,不该被当缺
   * policy 误报)。
   */
  private static final String LIST_BIZ_TABLES_SQL =
      "SELECT c.relname FROM pg_class c "
          + "JOIN pg_namespace n ON n.oid = c.relnamespace "
          + "WHERE n.nspname = ? AND c.relkind IN ('r','p') AND c.relispartition = false";

  private static final String CHECK_ENABLE_FORCE_SQL =
      "SELECT relrowsecurity, relforcerowsecurity FROM pg_class c "
          + "JOIN pg_namespace n ON n.oid = c.relnamespace "
          + "WHERE n.nspname = ? AND c.relname = ?";

  /**
   * policy <b>语义合规</b>检查 —— 不只验同名 policy 存在,还验 policy 真的在做租户隔离(防误建的同名坏 policy): 必须 {@code
   * cmd='ALL'}(FOR ALL)、{@code permissive='PERMISSIVE'}、有 {@code WITH CHECK}、且 {@code USING} 表达式引用了
   * {@code app.tenant_id}(防 {@code USING(true)} 放行全表)。接受 transition / strict 任一名(灰度兼容)。 任一性质不满足 →
   * 该表算"缺合规 policy"(health DOWN)。占位符由 ACCEPTED_POLICY_NAMES 长度生成。
   */
  private final String checkPolicySql;

  private final DataSource businessDataSource;

  /** biz 表豁免清单(不带 schema 前缀,如 {@code biz_table_schema});默认空。 */
  private final Set<String> exemptBizTables;

  public RlsClosedWorldChecker(DataSource businessDataSource, List<String> exemptBizTables) {
    this.businessDataSource = businessDataSource;
    this.exemptBizTables =
        exemptBizTables == null ? Set.of() : Set.copyOf(stripSchemaPrefix(exemptBizTables));
    this.checkPolicySql =
        "SELECT 1 FROM pg_policies WHERE schemaname = ? AND tablename = ? AND policyname IN ("
            + "?,".repeat(ACCEPTED_POLICY_NAMES.size() - 1)
            + "?)"
            // 语义合规:FOR ALL + PERMISSIVE + 有 WITH CHECK + USING 引用 app.tenant_id(防同名坏 policy)。
            // 'app.tenant_id' 是代码常量字面量,非外部输入,内联安全。
            + " AND cmd = 'ALL' AND permissive = 'PERMISSIVE' AND with_check IS NOT NULL"
            + " AND qual IS NOT NULL AND position('app.tenant_id' in qual) > 0";
  }

  private static List<String> stripSchemaPrefix(List<String> tables) {
    List<String> stripped = new ArrayList<>(tables.size());
    for (String t : tables) {
      int dot = t.indexOf('.');
      stripped.add(dot >= 0 ? t.substring(dot + 1) : t);
    }
    return stripped;
  }

  /**
   * 跑闭世界检查,返回缺失明细。connection / SQLException 由调用方决定如何处理(health 转 DOWN / fail-fast 抛异常),本方法只负责查。
   *
   * @return 缺失分类结果;全过则 {@link Result#isClean()} 为 true。
   */
  public Result check() throws SQLException {
    List<String> missingEnable = new ArrayList<>();
    List<String> missingForce = new ArrayList<>();
    List<String> missingPolicy = new ArrayList<>();

    Connection conn = DataSourceUtils.getConnection(businessDataSource);
    try {
      // 1. 闭世界扫真实 biz 表(已排除分区子表),减去豁免清单。
      Set<String> bizTables = new TreeSet<>();
      try (PreparedStatement ps = conn.prepareStatement(LIST_BIZ_TABLES_SQL)) {
        ps.setString(1, BIZ_SCHEMA);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String table = rs.getString(1);
            if (!exemptBizTables.contains(table)) {
              bizTables.add(table);
            }
          }
        }
      }
      for (String table : bizTables) {
        inspectTable(conn, BIZ_SCHEMA, table, missingEnable, missingForce, missingPolicy);
      }

      // 2. batch.process_staging 单独检查(在 batch schema,非闭世界扫描范围);不存在则跳过(单 worker 部署)。
      if (tableExists(conn, PROCESS_STAGING_SCHEMA, PROCESS_STAGING_TABLE)) {
        inspectTable(
            conn,
            PROCESS_STAGING_SCHEMA,
            PROCESS_STAGING_TABLE,
            missingEnable,
            missingForce,
            missingPolicy);
      }
    } finally {
      DataSourceUtils.releaseConnection(conn, businessDataSource);
    }
    return new Result(missingEnable, missingForce, missingPolicy);
  }

  private void inspectTable(
      Connection conn,
      String schema,
      String table,
      List<String> missingEnable,
      List<String> missingForce,
      List<String> missingPolicy)
      throws SQLException {
    String fqTable = schema + "." + table;
    try (PreparedStatement ps = conn.prepareStatement(CHECK_ENABLE_FORCE_SQL)) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          if (!rs.getBoolean(1)) {
            missingEnable.add(fqTable);
          }
          if (!rs.getBoolean(2)) {
            missingForce.add(fqTable);
          }
        }
      }
    }
    try (PreparedStatement ps = conn.prepareStatement(checkPolicySql)) {
      ps.setString(1, schema);
      ps.setString(2, table);
      for (int i = 0; i < ACCEPTED_POLICY_NAMES.size(); i++) {
        ps.setString(3 + i, ACCEPTED_POLICY_NAMES.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          missingPolicy.add(fqTable);
        }
      }
    }
  }

  private boolean tableExists(Connection conn, String schema, String table) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /** 闭世界检查结果:三类缺失(缺 ENABLE / 缺 FORCE / 缺 policy)。任一非空即不洁净。 */
  public record Result(
      List<String> missingEnable, List<String> missingForce, List<String> missingPolicy) {

    public boolean isClean() {
      return missingEnable.isEmpty() && missingForce.isEmpty() && missingPolicy.isEmpty();
    }

    /** 合并去重的「缺任一守护」表全名列表,供 fail-fast 异常文案与 health detail 用。 */
    public List<String> allMissingTables() {
      Set<String> all = new TreeSet<>();
      all.addAll(missingEnable);
      all.addAll(missingForce);
      all.addAll(missingPolicy);
      return List.copyOf(all);
    }
  }
}
