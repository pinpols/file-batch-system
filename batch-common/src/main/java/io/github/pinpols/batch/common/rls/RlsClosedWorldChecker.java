package io.github.pinpols.batch.common.rls;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Phase A · RLS 闭世界守护(closed-world)。
 *
 * <p><b>动机</b>:旧的健康检查遍历硬编码 {@code EXPECTED_RLS_TABLES} 清单。新增 biz 表漏配 RLS 时,因为不在清单里,health 仍报绿 →
 * 静默跨租户泄露。本检查器改为<b>扫真实 biz schema</b>:任何缺 RLS(ENABLE/FORCE)或缺 policy 的 biz 表都会被发现,新增表漏配不再无感。
 *
 * <p><b>分区子表</b>:通过父表访问时,父策略约束子表行;直接访问分区子表时,只应用子表策略。为防止分区子表被直接授权后绕过父表策略,
 * 闭世界检查会逐张检查带 {@code tenant_id} 的分区子表,要求子表同样启用并强制 RLS,且具有符合租户条件的策略。
 *
 * <p><b>扫描边界</b>:只扫描真实存在、包含 {@code tenant_id} 的 biz 租户表。非租户元数据表不属于 RLS 闭世界；若某张表虽包含 {@code
 * tenant_id} 但明确是非租户元数据,才把表名(不带 schema 前缀)加入 {@code exemptBizTables}。
 *
 * <p>{@code batch.process_staging} 在 batch schema(不在 biz),单独以固定全名检查。
 *
 * <p><b>多分片(tenant-routing)</b>:生产 {@code businessDataSource} 恒为 {@link AbstractRoutingDataSource}
 * (单片也被 {@code BusinessRoutingDataSourceFactory.singleShard} 包了一层)。<b>致命盲区</b>:{@code check()} 无
 * {@code TenantContext},路由 {@code determineCurrentLookupKey} 解析 tenant=null → 落 {@code
 * DEFAULT_KEY}(shard-0),于是<b>只扫默认片</b>。多片部署下,某个新扩 shard/silo 漏跑建策略 SQL 时,该片 biz.* 无行级隔离(同片多租户互读写),
 * 而启动 fail-fast / health 仍判绿。故本检查器遇路由 DS 时,<b>枚举其全部 target 分片</b>(绕过路由,直接对每片取连接)逐一闭世界校验, 缺失明细以
 * {@code shard-key:biz.table} 标注是哪个片缺哪张表;非路由的普通单 DS 走原逻辑(单片)。
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

  /** 运行期只接受 strict policy；transition 仅作为应急回滚脚本的临时状态。 */
  public static final List<String> ACCEPTED_POLICY_NAMES = List.of("tenant_isolation_strict");

  private static final String TENANT_POLICY_SHAPE = "tenant_id=current_setting'app.tenant_id',true";

  private static final String PROCESS_STAGING_POLICY_SHAPE = TENANT_POLICY_SHAPE
      + "ANDstaging_generation_id=current_setting'app.process_staging_generation_id',true";

  /**
   * 闭世界扫真实 biz 表,包含分区父表和带 {@code tenant_id} 的全部分区子表;子表允许直接访问时必须单独受 RLS 保护。
   */
  private static final String LIST_BIZ_TABLES_SQL = "SELECT c.relname FROM pg_class c "
      + "JOIN pg_namespace n ON n.oid = c.relnamespace "
      + "JOIN information_schema.columns col "
      + "ON col.table_schema = n.nspname AND col.table_name = c.relname "
      + "AND col.column_name = 'tenant_id' "
      + "WHERE n.nspname = ? AND c.relkind IN ('r','p')";

  private static final String CHECK_ENABLE_FORCE_SQL =
      "SELECT relrowsecurity, relforcerowsecurity FROM pg_class c "
          + "JOIN pg_namespace n ON n.oid = c.relnamespace "
          + "WHERE n.nspname = ? AND c.relname = ?";

  /**
   * 只接受本项目生成的 strict 策略形态：FOR ALL / PERMISSIVE / TO PUBLIC；普通租户表按 tenant_id 隔离，
   * PROCESS staging 还必须按 staging_generation_id 收窄访问。
   * 归一化仅消除 PostgreSQL 的空白、括号及 text 类型转换，不把“含 tenant_id 字样”等同于隔离证明。
   * 额外 PERMISSIVE 策略会与 strict 做 OR，未知组合必须拒绝；额外 RESTRICTIVE 策略可保留。
   */
  private final String checkPolicySql;

  private final DataSource businessDataSource;

  /** biz 表豁免清单(不带 schema 前缀,如 {@code biz_table_schema});默认空。 */
  private final Set<String> exemptBizTables;

  public RlsClosedWorldChecker(DataSource businessDataSource, List<String> exemptBizTables) {
    this.businessDataSource = businessDataSource;
    if (EmptyChecks.isNull(exemptBizTables)) {
      this.exemptBizTables = Set.of();
    } else {
      List<String> schemaFreeTables = stripSchemaPrefix(exemptBizTables);
      this.exemptBizTables = Set.copyOf(schemaFreeTables);
    }
    String acceptedPolicyPlaceholders = "?,".repeat(ACCEPTED_POLICY_NAMES.size() - 1) + "?";
    this.checkPolicySql = """
        SELECT 1 FROM pg_policies expected_policy
        WHERE expected_policy.schemaname = ? AND expected_policy.tablename = ?
          AND expected_policy.policyname IN (%s)
          AND expected_policy.cmd = 'ALL' AND expected_policy.permissive = 'PERMISSIVE'
          AND expected_policy.roles = ARRAY['public']::name[]
          AND regexp_replace(expected_policy.qual, '[[:space:]()]|::text', '', 'g') = ?
          AND regexp_replace(expected_policy.with_check, '[[:space:]()]|::text', '', 'g') = ?
          AND NOT EXISTS (SELECT 1 FROM pg_policies other_policy
              WHERE other_policy.schemaname = expected_policy.schemaname
                AND other_policy.tablename = expected_policy.tablename
                AND other_policy.permissive = 'PERMISSIVE'
                AND other_policy.policyname <> expected_policy.policyname)
        """.formatted(acceptedPolicyPlaceholders).stripTrailing();
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

    if (businessDataSource instanceof AbstractRoutingDataSource routing) {
      // 多分片:枚举全部 target 分片逐一校验,而非只走路由默认片。无租户上下文时路由会落 shard-0,
      // 漏扫其余 shard/silo(漏配 RLS 的片会静默放行 → 同片跨租户互读写),这里绕过路由直接对每片取连接。
      Map<DataSource, String> shards = distinctShards(routing);
      boolean labelShards = shards.size() > 1;
      for (Map.Entry<DataSource, String> entry : shards.entrySet()) {
        String shardLabel = labelShards ? entry.getValue() : null;
        try (Connection conn = entry.getKey().getConnection()) {
          scanConnection(conn, shardLabel, missingEnable, missingForce, missingPolicy);
        }
      }
    } else {
      // 普通单 DS(非路由):保持原逻辑,经 DataSourceUtils 参与可能的环境事务。
      Connection conn = DataSourceUtils.getConnection(businessDataSource);
      try {
        scanConnection(conn, null, missingEnable, missingForce, missingPolicy);
      } finally {
        DataSourceUtils.releaseConnection(conn, businessDataSource);
      }
    }
    return new Result(missingEnable, missingForce, missingPolicy);
  }

  /**
   * 枚举路由 DS 的全部去重 target 分片(placement key → DataSource;不同 key 指向同一物理库时合并 key 标签,避免重复扫同一库)。 一并纳入
   * resolvedDefaultDataSource,防装配方把 default 设成不在 targets 里的额外库时漏扫。
   */
  private static Map<DataSource, String> distinctShards(AbstractRoutingDataSource routing) {
    Map<DataSource, String> shards = new LinkedHashMap<>();
    routing
        .getResolvedDataSources()
        .forEach((key, ds) -> shards.merge(ds, String.valueOf(key), (a, b) -> a + "," + b));
    DataSource defaultDs = routing.getResolvedDefaultDataSource();
    if (defaultDs != null) {
      shards.putIfAbsent(defaultDs, "default");
    }
    return shards;
  }

  /** 对单条连接跑一次完整闭世界扫描(biz.* + batch.process_staging)。shardLabel 非空时给缺失表全名加 {@code 片:} 前缀。 */
  private void scanConnection(
      Connection conn,
      String shardLabel,
      List<String> missingEnable,
      List<String> missingForce,
      List<String> missingPolicy)
      throws SQLException {
    InspectionFindings findings = new InspectionFindings(missingEnable, missingForce, missingPolicy);
    // 1. 闭世界扫真实 biz 租户表,包括分区父表和所有租户分区子表。
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
      inspectTable(conn, shardLabel, BIZ_SCHEMA, table, findings);
    }

    // 2. batch.process_staging 的父表与分区子表一并检查;该表位于 batch schema,单独限定范围。
    if (tableExists(conn, PROCESS_STAGING_SCHEMA, PROCESS_STAGING_TABLE)) {
      inspectTable(
          conn,
          shardLabel,
          PROCESS_STAGING_SCHEMA,
          PROCESS_STAGING_TABLE,
          findings,
          true);
      for (String partition : listProcessStagingPartitions(conn)) {
        inspectTable(
            conn,
            shardLabel,
            PROCESS_STAGING_SCHEMA,
            partition,
            findings,
            true);
      }
    }
  }

  private List<String> listProcessStagingPartitions(Connection conn) throws SQLException {
    List<String> partitions = new ArrayList<>();
    String sql = "SELECT child.relname FROM pg_inherits i "
        + "JOIN pg_class parent ON parent.oid = i.inhparent "
        + "JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace "
        + "JOIN pg_class child ON child.oid = i.inhrelid "
        + "JOIN pg_namespace child_ns ON child_ns.oid = child.relnamespace "
        + "JOIN information_schema.columns column_info "
        + "ON column_info.table_schema = child_ns.nspname "
        + "AND column_info.table_name = child.relname AND column_info.column_name = 'tenant_id' "
        + "WHERE parent_ns.nspname = ? AND parent.relname = ? "
        + "AND child_ns.nspname = ? ORDER BY child.relname";
    try (PreparedStatement statement = conn.prepareStatement(sql)) {
      statement.setString(1, PROCESS_STAGING_SCHEMA);
      statement.setString(2, PROCESS_STAGING_TABLE);
      statement.setString(3, PROCESS_STAGING_SCHEMA);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          partitions.add(resultSet.getString(1));
        }
      }
    }
    return partitions;
  }

  private void inspectTable(
      Connection conn,
      String shardLabel,
      String schema,
      String table,
      InspectionFindings findings)
      throws SQLException {
    inspectTable(
        conn, shardLabel, schema, table, findings, false);
  }

  private void inspectTable(
      Connection conn,
      String shardLabel,
      String schema,
      String table,
      InspectionFindings findings,
      boolean processStagingPolicy)
      throws SQLException {
    String fqTable = (shardLabel == null ? "" : shardLabel + ":") + schema + "." + table;
    try (PreparedStatement ps = conn.prepareStatement(CHECK_ENABLE_FORCE_SQL)) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          if (!rs.getBoolean(1)) {
            findings.missingEnable().add(fqTable);
          }
          if (!rs.getBoolean(2)) {
            findings.missingForce().add(fqTable);
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
      String expectedShape =
          processStagingPolicy ? PROCESS_STAGING_POLICY_SHAPE : TENANT_POLICY_SHAPE;
      int shapeParameter = 3 + ACCEPTED_POLICY_NAMES.size();
      ps.setString(shapeParameter, expectedShape);
      ps.setString(shapeParameter + 1, expectedShape);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          findings.missingPolicy().add(fqTable);
        }
      }
    }
  }

  /** 同一次扫描共享三类缺失清单，保持原连接与累积顺序。 */
  private record InspectionFindings(
      List<String> missingEnable, List<String> missingForce, List<String> missingPolicy) {}

  private boolean tableExists(Connection conn, String schema, String table) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
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
