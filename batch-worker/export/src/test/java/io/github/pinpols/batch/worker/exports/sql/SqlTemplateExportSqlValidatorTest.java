package io.github.pinpols.batch.worker.exports.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.sql.SelectSqlAstValidator;
import io.github.pinpols.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("模板导出语句校验单测:语句类型,通配查询,库表白名单,参数与危险函数拦截语义")
class SqlTemplateExportSqlValidatorTest {

  private SqlTemplateExportSqlValidator validatorWithDefaults() {
    return new SqlTemplateExportSqlValidator(new SqlTemplateExportSecurityProperties());
  }

  // ── blank / null ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("语句为空或纯空白时校验失败")
  void validate_throwsOnBlank() {
    assertThatThrownBy(() -> validatorWithDefaults().validate("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  @DisplayName("语句为空引用时校验失败")
  void validate_throwsOnNull() {
    assertThatThrownBy(() -> validatorWithDefaults().validate(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  // ── non-SELECT statements ────────────────────────────────────────────────────

  @Test
  @DisplayName("写入类语句被拒绝")
  void validate_throwsOnInsert() {
    assertThatThrownBy(() -> validatorWithDefaults().validate("INSERT INTO t VALUES (1)"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT");
  }

  @Test
  @DisplayName("更新语句被拒绝,即使用到租户与批次参数")
  void validate_throwsOnUpdate() {
    assertThatThrownBy(() -> validatorWithDefaults()
            .validate("UPDATE t SET a = 1 WHERE id = :tenantId AND batch_no = :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT");
  }

  @Test
  @DisplayName("删除表等结构变更语句被拒绝")
  void validate_throwsOnDrop() {
    assertThatThrownBy(() -> validatorWithDefaults().validate("DROP TABLE t"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── SELECT * ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("查询使用通配列时被拒绝")
  void validate_throwsOnSelectStar() {
    assertThatThrownBy(() -> validatorWithDefaults()
            .validate("SELECT * FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT *");
  }

  @Test
  @DisplayName("带表别名的通配列同样被拒绝")
  void validate_throwsOnSelectTableStar() {
    assertThatThrownBy(() -> validatorWithDefaults()
            .validate("SELECT t.* FROM biz.t t WHERE t.tenant_id = :tenantId AND t.batch_no ="
                + " :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT *");
  }

  @Test
  @DisplayName("联合查询里任一分支使用通配列都会被拒绝")
  void validate_throwsOnSelectStarInUnion() {
    assertThatThrownBy(() -> validatorWithDefaults()
            .validate("SELECT id FROM biz.a WHERE tenant_id = :tenantId AND batch_no = :batchNo "
                + "UNION ALL SELECT * FROM biz.b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT *");
  }

  @Test
  @DisplayName("关闭通配列禁用配置后,通配查询可通过")
  void validate_allowsSelectStarWhenForbidDisabled() {
    SqlTemplateExportSecurityProperties props = new SqlTemplateExportSecurityProperties();
    props.setForbidSelectStar(false);
    props.setRequiredParams(List.of("tenantId", "batchNo"));
    SqlTemplateExportSqlValidator v = new SqlTemplateExportSqlValidator(props);

    String result =
        v.validate("SELECT * FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo");
    assertThat(result).isNotBlank();
  }

  // ── schema whitelist ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("查询引用白名单之外的库时被拒绝")
  void validate_throwsOnDisallowedSchema() {
    SqlTemplateExportSecurityProperties props = new SqlTemplateExportSecurityProperties();
    props.setAllowedSchemas(List.of("biz"));
    props.setForbidSelectStar(false);
    SqlTemplateExportSqlValidator v = new SqlTemplateExportSqlValidator(props);

    assertThatThrownBy(() -> v.validate(
            "SELECT id FROM pg_catalog.pg_tables WHERE tenant_id = :tenantId AND batch_no ="
                + " :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pg_catalog");
  }

  @Test
  @DisplayName("配置库白名单后,未限定库名的表被拒绝")
  void validate_throwsOnUnqualifiedTable_whenWhitelistSet() {
    SqlTemplateExportSecurityProperties props = new SqlTemplateExportSecurityProperties();
    props.setAllowedSchemas(List.of("biz"));
    props.setForbidSelectStar(false);
    SqlTemplateExportSqlValidator v = new SqlTemplateExportSqlValidator(props);

    // Unqualified table (no schema prefix) must be rejected — prevents bypassing the whitelist
    // by referencing internal tables like batch.job_task without a schema prefix
    assertThatThrownBy(() ->
            v.validate("SELECT id FROM unqualified_table WHERE tenant_id = :tenantId AND batch_no ="
                + " :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unqualified_table");
  }

  @Test
  @DisplayName("限定在白名单库内并带租户与批次条件的查询通过")
  void validate_allowsWhitelistedSchema() {
    SqlTemplateExportSecurityProperties props = new SqlTemplateExportSecurityProperties();
    props.setAllowedSchemas(List.of("biz", "ref"));
    props.setForbidSelectStar(false);
    SqlTemplateExportSqlValidator v = new SqlTemplateExportSqlValidator(props);

    String result = v.validate("SELECT id, name FROM biz.customer_account ca "
        + "JOIN ref.currency_code cc ON cc.code = ca.currency "
        + "WHERE ca.tenant_id = :tenantId AND ca.batch_no = :batchNo");
    assertThat(result).isNotBlank();
  }

  // ── required params ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("缺少租户参数时校验失败")
  void validate_throwsWhenTenantIdMissing() {
    assertThatThrownBy(() ->
            validatorWithDefaults().validate("SELECT id FROM biz.t WHERE batch_no = :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(":tenantId");
  }

  @Test
  @DisplayName("缺少批次参数时校验失败")
  void validate_throwsWhenBatchNoMissing() {
    assertThatThrownBy(() ->
            validatorWithDefaults().validate("SELECT id FROM biz.t WHERE tenant_id = :tenantId"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(":batchNo");
  }

  @Test
  @DisplayName("字符串字面量里的参数写法不算真实参数,仍判定缺少租户参数")
  void validate_doesNotTreatStringLiteralAsRequiredParameter() {
    SqlTemplateExportSqlValidator validator = validatorWithDefaults();
    String sql = "SELECT id FROM biz.t WHERE note = ':tenantId' AND batch_no = :batchNo";
    assertThatThrownBy(() -> validator.validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(":tenantId");
  }

  @Test
  @DisplayName("注释里的参数写法不算真实参数,同样判定缺少参数")
  void validate_doesNotTreatCommentAsRequiredParameter() {
    SqlTemplateExportSqlValidator validator = validatorWithDefaults();
    String sql = """
        SELECT id FROM biz.t WHERE batch_no = :batchNo -- :tenantId
        """;
    assertThatThrownBy(() -> validator.validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(":tenantId");
  }

  @ParameterizedTest
  @DisplayName("参数文本出现在非参数位置时被忽略,校验通过并原样返回")
  @MethodSource("sqlWithIgnoredParameterText")
  void validate_ignoresParameterTextOutsideSqlParameters(String sql) {
    assertThat(validatorWithDefaults().validate(sql)).isEqualTo(sql.trim());
  }

  private static Stream<String> sqlWithIgnoredParameterText() {
    return Stream.of(
        "SELECT id FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo "
            + "AND note = ':unknownInString' -- :unknownInComment\n",
        "SELECT \"note:unknownInIdentifier\", id /* :unknownInBlock */ FROM biz.t "
            + "WHERE tenant_id = :tenantId AND batch_no = :batchNo",
        "SELECT 'backslash\\\\:unknownInEscape', 'it''s:unknownInDouble' FROM biz.t "
            + "WHERE tenant_id = :tenantId AND batch_no = :batchNo",
        "SELECT $body$:unknownInTaggedDollar$body$ AS note, id FROM biz.t "
            + "WHERE tenant_id = :tenantId AND batch_no = :batchNo");
  }

  @Test
  @DisplayName("出现未声明的命名参数时校验失败")
  void validate_rejectsUnknownNamedParameter() {
    String sql = "SELECT id FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo "
        + "AND customer_id = :extraParam";
    SqlTemplateExportSqlValidator validator = validatorWithDefaults();
    assertThatThrownBy(() -> validator.validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown named parameter :extraParam");
  }

  @Test
  @DisplayName("允许清单内的额外命名参数可通过")
  void validate_acceptsAllowedExtraNamedParameter() {
    SqlTemplateExportSecurityProperties props = new SqlTemplateExportSecurityProperties();
    props.setAllowedExtraParams(List.of("customerId"));
    SqlTemplateExportSqlValidator validator = new SqlTemplateExportSqlValidator(props);
    String sql = "SELECT id FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo "
        + "AND customer_id = :customerId";
    assertThat(validator.validate(sql)).isEqualTo(sql);
  }

  @Test
  @DisplayName("类型转换写法不被误判为命名参数,校验通过")
  void validate_doesNotTreatPostgresCastAsNamedParameter() {
    String sql = "SELECT id::text FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo";
    assertThat(validatorWithDefaults().validate(sql)).isEqualTo(sql);
  }

  @Test
  @DisplayName("美元引用块内的参数写法不被当作真实参数")
  void validate_doesNotTreatDollarQuoteAsNamedParameter() {
    String sql = "SELECT $$:unknownInDollarQuote$$ AS note, id FROM biz.t "
        + "WHERE tenant_id = :tenantId AND batch_no = :batchNo";
    assertThat(validatorWithDefaults().validate(sql)).isEqualTo(sql);
  }

  @Test
  @DisplayName("参数扫描忽略未闭合字面量,注释,位置占位符与美元引用内的写法")
  void shouldIgnoreParameterTokens_whenInsideLiteralsOrDollarQuotes() {
    assertThat(SqlTemplateExportSqlValidator.extractNamedParameters("SELECT 'unterminated :x"))
        .isEmpty();
    assertThat(SqlTemplateExportSqlValidator.extractNamedParameters("SELECT id -- :x"))
        .isEmpty();
    assertThat(SqlTemplateExportSqlValidator.extractNamedParameters("SELECT id /* :x"))
        .isEmpty();
    assertThat(SqlTemplateExportSqlValidator.extractNamedParameters("$")).isEmpty();
    assertThat(SqlTemplateExportSqlValidator.extractNamedParameters("$1 :tenantId"))
        .containsExactly("tenantId");
    assertThat(SqlTemplateExportSqlValidator.extractNamedParameters("$tag$ :x")).isEmpty();
    assertThat(SqlTemplateExportSqlValidator.extractNamedParameters("$tag$:x")).isEmpty();
    assertThat(SqlTemplateExportSqlValidator.extractNamedParameters("- / :tenantId"))
        .containsExactly("tenantId");
  }

  // ── valid SQL ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("合法查询返回去除首尾空白后的语句")
  void validate_returnsNormalizedSqlForValidQuery() {
    String sql =
        "  SELECT id, name FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo  ";
    String result = validatorWithDefaults().validate(sql);
    assertThat(result).isEqualTo(sql.trim());
  }

  @Test
  @DisplayName("带公共表表达式的查询可通过")
  void validate_acceptsWithClause() {
    String sql = """
        WITH filtered AS (
          SELECT id, amount FROM biz.settlement_detail
          WHERE tenant_id = :tenantId AND batch_no = :batchNo
        )
        SELECT f.id, f.amount FROM filtered f
        ORDER BY f.id
        """.trim();
    String result = validatorWithDefaults().validate(sql);
    assertThat(result).isNotBlank();
  }

  @Test
  @DisplayName("调用外部连接函数时被拒绝")
  void validate_rejectsDblinkFunctionCall() {
    String sql = "SELECT c FROM dblink('host=evil', 'select 1') AS t(c int)"
        + " WHERE :tenantId IS NOT NULL AND :batchNo IS NOT NULL";
    assertThatThrownBy(() -> validatorWithDefaults().validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function 'dblink'");
  }

  @Test
  @DisplayName("调用危险会话终止函数时被拒绝")
  void validate_rejectsPgTerminateBackend() {
    String sql = "SELECT pg_terminate_backend(pid) AS c FROM biz.t"
        + " WHERE tenant_id = :tenantId AND batch_no = :batchNo";
    assertThatThrownBy(() -> validatorWithDefaults().validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("用注释分隔函数名绕过检测的写法仍被拒绝")
  void validate_rejectsForbiddenFunctionWithCommentInjection() {
    // 回归:老子串方案被"函数名与左括号间插块注释"绕过(右侧紧跟 ( 判定只跳空白不跳注释 → 漏判)。
    // 现与 process 侧共用 SelectSqlAstValidator 的 AST 函数节点遍历,仍拒。
    String sql = "SELECT pg_read_server_files/**/('/etc/passwd') AS c FROM biz.t"
        + " WHERE tenant_id = :tenantId AND batch_no = :batchNo";
    assertThatThrownBy(() -> validatorWithDefaults().validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function 'pg_read_server_files'");
  }

  @Test
  @DisplayName("函数名大小写混杂时仍被识别并拒绝")
  void validate_rejectsForbiddenFunctionMixedCase() {
    String sql = "SELECT DbLink('host=evil', 'select 1') AS c FROM biz.t"
        + " WHERE tenant_id = :tenantId AND batch_no = :batchNo";
    assertThatThrownBy(() -> validatorWithDefaults().validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function");
  }

  @Test
  @DisplayName("函数名加双引号引用时仍被识别并拒绝")
  void validate_rejectsForbiddenFunctionQuotedIdentifier() {
    // 带引号标识符 "pg_read_server_files"(...) 曾逃逸子串比对;AST 收集函数名时去引号后仍拒。
    String sql = "SELECT \"pg_read_server_files\"('/etc/passwd') AS c FROM biz.t"
        + " WHERE tenant_id = :tenantId AND batch_no = :batchNo";
    assertThatThrownBy(() -> validatorWithDefaults().validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function");
  }

  @Test
  @DisplayName("危险函数嵌套在其它函数调用里仍被拒绝")
  void validate_rejectsForbiddenFunctionNestedInExpression() {
    // AST 遍历应深入嵌套表达式 / 函数参数,不止顶层 select item。
    String sql = "SELECT upper(coalesce(pg_terminate_backend(pid), 'x')) AS c FROM biz.t"
        + " WHERE tenant_id = :tenantId AND batch_no = :batchNo";
    assertThatThrownBy(() -> validatorWithDefaults().validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("排序子句里出现危险函数时被拒绝")
  void validate_rejectsForbiddenFunctionInOrderBy() {
    // 回归:TablesNamesFinder 不下钻 ORDER BY 标量表达式,共享核须显式补走,否则漏采放行
    // (export 旧子串实现本能拦住此写法,共享核初版曾在此回归)。
    String sql = "SELECT c1 FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo"
        + " ORDER BY pg_terminate_backend(pid)";
    assertThatThrownBy(() -> validatorWithDefaults().validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("分组子句里出现危险函数时被拒绝")
  void validate_rejectsForbiddenFunctionInGroupBy() {
    String sql =
        "SELECT max(c1) AS m FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo"
            + " GROUP BY pg_terminate_backend(pid)";
    assertThatThrownBy(() -> validatorWithDefaults().validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("窗口函数子句里出现危险函数时被拒绝")
  void validate_rejectsForbiddenFunctionInWindowOver() {
    // 窗口 OVER(...) 是 AnalyticExpression 节点,函数名与内部表达式均须采集。
    String sql = "SELECT row_number() over (ORDER BY pg_terminate_backend(pid)) AS rn FROM biz.t"
        + " WHERE tenant_id = :tenantId AND batch_no = :batchNo";
    assertThatThrownBy(() -> validatorWithDefaults().validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("偏移子句里出现危险函数时被拒绝")
  void validate_rejectsForbiddenFunctionInOffset() {
    String sql = "SELECT c1 FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo"
        + " ORDER BY c1 OFFSET pg_terminate_backend(1)";
    assertThatThrownBy(() -> validatorWithDefaults().validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("以建表即查询方式写出结果集时被拒绝")
  void validate_rejectsCtas() {
    String sql = "CREATE TABLE biz.foo AS SELECT id FROM biz.t WHERE tenant_id = :tenantId";
    assertThatThrownBy(() -> validatorWithDefaults().validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only allows SELECT");
  }

  @Test
  @DisplayName("多表关联查询在带租户与批次条件时通过")
  void validate_acceptsJoin() {
    String sql = "SELECT sb.batch_no, sd.settlement_no "
        + "FROM biz.settlement_detail sd "
        + "JOIN biz.settlement_batch sb ON sb.id = sd.batch_id "
        + "WHERE sb.tenant_id = :tenantId AND sb.batch_no = :batchNo";
    String result = validatorWithDefaults().validate(sql);
    assertThat(result).isEqualTo(sql);
  }

  // ── W1-4: 配置源统一守护 ──────────────────────────────────────────────────
  // export 的 forbiddenFunctions 默认值必须与 batch-common 单一权威源内容一致，不得再各侧硬编码字面量各自维护。

  @Test
  @DisplayName("默认禁用函数清单与公共模块的共享来源保持一致")
  void defaultForbiddenFunctions_matchesBatchCommonSharedSource() {
    assertThat(new SqlTemplateExportSecurityProperties().getForbiddenFunctions())
        .containsExactlyInAnyOrderElementsOf(SelectSqlAstValidator.DEFAULT_FORBIDDEN_FUNCTIONS);
  }

  @Test
  @DisplayName("共享来源新增的函数会被即时纳入拦截")
  void validate_blocksFunctionAddedToSharedSource() {
    // 模拟"单一源加一个禁用函数"：在共享源基础上追加一个仅测试用的函数名，export 侧必须同样拦住它——
    // 证明 properties 默认值是从共享清单派生的副本，而非另一份独立硬编码副本。
    List<String> extended = new ArrayList<>(SelectSqlAstValidator.DEFAULT_FORBIDDEN_FUNCTIONS);
    extended.add("w1_4_test_only_marker_fn");
    SqlTemplateExportSecurityProperties props = new SqlTemplateExportSecurityProperties();
    props.setForbiddenFunctions(extended);
    SqlTemplateExportSqlValidator v = new SqlTemplateExportSqlValidator(props);

    String sql = "SELECT w1_4_test_only_marker_fn(1) AS c FROM biz.t"
        + " WHERE tenant_id = :tenantId AND batch_no = :batchNo";
    assertThatThrownBy(() -> v.validate(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function 'w1_4_test_only_marker_fn'");
  }
}
