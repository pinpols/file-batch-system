package io.github.pinpols.batch.worker.processes.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.sql.SelectSqlAstValidator;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SQL 转换计算语句校验:只读与库白名单、禁用函数各语法位置的拦截,以及行数上限与共享清单守护")
class SqlTransformComputeSqlValidatorTest {

  /**
   * 用户 SQL 校验失败统一为 {@link BizException}(INVALID_ARGUMENT + error.process.sql_validation), 详情在
   * messageArgs[0]。这样上层 catch(BizException) 归类为 BUSINESS_ERROR(确定性失败,不触发重试风暴), 而非裸
   * IllegalArgumentException 落到 catch(Exception) 被误判 INFRA_ERROR。
   */
  private static void assertRejected(ThrowingCallable call, String detailSubstring) {
    assertThatThrownBy(call).isInstanceOf(BizException.class).satisfies(e -> {
      BizException be = (BizException) e;
      assertThat(be.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT);
      if (detailSubstring != null) {
        assertThat(be.getMessageArgs()[0].toString()).contains(detailSubstring);
      }
    });
  }

  @Test
  @DisplayName("允许清单内的库可以查询,校验通过并原样返回语句")
  void validateSelect_allowsSelectFromAllowlistedSchema() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    String sql = validator.validateSelect(
        "select tenant_id, account_id from biz.order_event where tenant_id = :tenantId");

    assertThat(sql).contains("biz.order_event");
  }

  @Test
  @DisplayName("删除语句等写操作被拒绝,并说明只允许查询")
  void validateSelect_rejectsDml() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    assertRejected(
        () -> validator.validateSelect("delete from biz.order_event"), "only allows SELECT");
  }

  @Test
  @DisplayName("查询系统目录等未在允许清单内的库被拒绝,并提示库未被允许")
  void validateSelect_rejectsDisallowedSchema() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select id from pg_catalog.pg_tables where schemaname = 'biz'"),
        "disallowed schema");
  }

  @Test
  @DisplayName("用户校验语句读取暂存表之外的业务表时被拒绝,并说明只能读暂存表")
  void validateUserCheckSelect_rejectsReadingNonStagingTables() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    assertRejected(
        () -> validator.validateUserCheckSelect(
            "select true AS pass, 'ok' AS message from biz.order_event"),
        "validation SQL may only read batch.process_staging");
  }

  @Test
  @DisplayName("建表并查数据的组合语句被拒绝,并说明只允许查询")
  void validateSelect_rejectsCtasCreateTableAsSelect() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    // CTAS 被 JSqlParser 解析为 CreateTable, instanceof Select 检查直接拦,但要有显式守护
    assertRejected(
        () -> validator.validateSelect(
            "create table biz.foo as select tenant_id from biz.order_event"),
        "only allows SELECT");
  }

  @Test
  @DisplayName("修改检索路径的语句被拒绝,以免绕过库白名单")
  void validateSelect_rejectsSetSearchPath() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    assertRejected(() -> validator.validateSelect("set search_path = public, biz"), null);
  }

  @Test
  @DisplayName("调用远端连接函数访问外部库时被拒绝,并指明该函数被禁用")
  void validateSelect_rejectsDblinkFunction() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect("select c from dblink('host=evil', 'select 1') as t(c int)"),
        "forbidden function 'dblink'");
  }

  @Test
  @DisplayName("函数名与左括号之间插入注释试图绕过时仍被拒绝,并指明该函数被禁用")
  void validateSelect_rejectsForbiddenFunctionWithCommentInjection() {
    // 回归:老子串方案被"函数名与左括号间插块注释"绕过(右侧紧跟 ( 判定只跳空白不跳注释 → 漏判)。
    // 现走 AST 函数节点,仍拒。
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect("select pg_read_server_files/**/('/etc/passwd') as c"),
        "forbidden function 'pg_read_server_files'");
  }

  @Test
  @DisplayName("禁用函数嵌套在其它函数参数里时仍被拒绝,并指明该函数被禁用")
  void validateSelect_rejectsForbiddenFunctionNestedInExpression() {
    // AST 遍历应深入嵌套表达式 / 函数参数,不止顶层 select item。
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select upper(coalesce(pg_terminate_backend(pid), 'x')) from biz.t"),
        "forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("禁用函数大小写混写时仍被拒绝,不因大小写差异漏判")
  void validateSelect_rejectsForbiddenFunctionMixedCase() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect("select DbLink('host=evil', 'select 1') as c from biz.t"),
        "forbidden function");
  }

  @Test
  @DisplayName("禁用函数用带引号标识符书写时仍被拒绝,不因引号逃逸比对")
  void validateSelect_rejectsForbiddenFunctionQuotedIdentifier() {
    // 带引号标识符 "pg_read_server_files"(...) 曾逃逸子串比对;AST 收集函数名时去引号后仍拒。
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select \"pg_read_server_files\"('/etc/passwd') as c from biz.t"),
        "forbidden function");
  }

  @Test
  @DisplayName("禁用函数出现在排序表达式中时仍被拒绝,并指明该函数被禁用")
  void validateSelect_rejectsForbiddenFunctionInOrderBy() {
    // 回归:TablesNamesFinder 不下钻 ORDER BY 标量表达式,共享核须显式补走,否则漏采放行。
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect("select c1 from biz.t where tenant_id = :tenantId"
            + " order by pg_terminate_backend(pid)"),
        "forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("禁用函数出现在分组表达式中时仍被拒绝,并指明该函数被禁用")
  void validateSelect_rejectsForbiddenFunctionInGroupBy() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select max(c1) from biz.t group by pg_terminate_backend(pid)"),
        "forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("禁用函数出现在窗口定义内时仍被拒绝,并指明该函数被禁用")
  void validateSelect_rejectsForbiddenFunctionInWindowOver() {
    // 窗口 OVER(...) 是 AnalyticExpression 节点,函数名与内部表达式均须采集。
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select row_number() over (order by pg_terminate_backend(pid)) as rn from biz.t"),
        "forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("禁用函数出现在偏移量表达式中时仍被拒绝,并指明该函数被禁用")
  void validateSelect_rejectsForbiddenFunctionInOffset() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select c1 from biz.t order by c1 offset pg_terminate_backend(1)"),
        "forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("查询中直接调用终止会话函数时被拒绝,并指明该函数被禁用")
  void validateSelect_rejectsPgTerminateBackend() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select pg_terminate_backend(pid) from biz.fake where tenant_id = :tenantId"),
        "forbidden function 'pg_terminate_backend'");
  }

  @Test
  @DisplayName("远端连接家族函数与延时函数分别被拒绝,同族变体不再放行")
  void validateSelect_rejectsDblinkFamilyAndSleepFor() {
    // 双侧一致:worker 侧默认清单也补齐 pg_sleep_for + 家族前缀匹配(dblink → dblink_exec)。
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select dblink_exec('h','drop table biz.t') from biz.t where tenant_id ="
                + " :tenantId"),
        "forbidden function");
    assertRejected(
        () -> validator.validateSelect(
            "select pg_sleep_for('5s') from biz.t where tenant_id = :tenantId"),
        "forbidden function");
  }

  @Test
  @DisplayName("开启行数上限要求后,未带限制行数的查询被拒绝并提示需要限制")
  void validateSelect_requireLimit_rejectsUnboundedQuery() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setRequireLimit(true);
    security.setMaxLimitRows(10_000L);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select tenant_id from biz.order_event where tenant_id = :tenantId"),
        "LIMIT");
  }

  @Test
  @DisplayName("限制行数超过配置上限时被拒绝,并提示超出上限")
  void validateSelect_requireLimit_rejectsOverMaxLimit() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setRequireLimit(true);
    security.setMaxLimitRows(10_000L);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select tenant_id from biz.order_event where tenant_id = :tenantId limit 50000"),
        "exceeds max");
  }

  @Test
  @DisplayName("限制行数落在配置上限内时校验通过,语句原样返回")
  void validateSelect_requireLimit_acceptsLimitWithinBound() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setRequireLimit(true);
    security.setMaxLimitRows(10_000L);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    String sql = validator.validateSelect(
        "select tenant_id from biz.order_event where tenant_id = :tenantId limit 5000");

    assertThat(sql).contains("limit 5000");
  }

  // ── S8: 非数值 LIMIT 不得绕过 maxLimitRows 上限 ──────────────────────────────
  @Test
  @DisplayName("限制行数写成参数占位符时被拒绝,并提示必须为数值,避免绕过上限")
  void validateSelect_requireLimit_rejectsParameterLimit() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setRequireLimit(true);
    security.setMaxLimitRows(10_000L);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    // LIMIT :p 之前被 catch 后当 0 放行 → maxLimitRows 上限被完全绕过。
    assertRejected(
        () -> validator.validateSelect(
            "select tenant_id from biz.order_event where tenant_id = :tenantId limit"
                + " :pageSize"),
        "numeric");
  }

  @Test
  @DisplayName("限制行数写成子查询时被拒绝,并提示必须为数值,避免绕过上限")
  void validateSelect_requireLimit_rejectsSubqueryLimit() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setRequireLimit(true);
    security.setMaxLimitRows(10_000L);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select tenant_id from biz.order_event where tenant_id = :tenantId"
                + " limit (select 999999)"),
        "numeric");
  }

  @Test
  @DisplayName("用户校验语句只读暂存表时校验通过,语句原样返回")
  void validateUserCheckSelect_allowsReadingProcessStaging() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    String sql = validator.validateUserCheckSelect(
        "select bool_and(tenant_id = :tenantId) AS pass from batch.process_staging"
            + " where batch_key = :batchKey");

    assertThat(sql).contains("batch.process_staging");
  }

  // ── W1-4: 配置源统一守护 ──────────────────────────────────────────────────
  // process 的 forbiddenFunctions 默认值必须与 batch-common 单一权威源内容一致，不得再各侧硬编码字面量各自维护。

  @Test
  @DisplayName("禁用函数的默认清单与共享权威源完全一致,不各自维护硬编码副本")
  void defaultForbiddenFunctions_matchesBatchCommonSharedSource() {
    assertThat(new SqlTransformComputeSecurityProperties().getForbiddenFunctions())
        .containsExactlyInAnyOrderElementsOf(SelectSqlAstValidator.DEFAULT_FORBIDDEN_FUNCTIONS);
  }

  @Test
  @DisplayName("共享清单新增一个禁用函数后,校验同样拦截它,证明默认清单派生于共享源")
  void validateSelect_blocksFunctionAddedToSharedSource() {
    // 模拟"单一源加一个禁用函数"：在共享源基础上追加一个仅测试用的函数名，process 侧必须同样拦住它——
    // 证明 properties 默认值是从共享清单派生的副本，而非另一份独立硬编码副本。
    List<String> extended = new ArrayList<>(SelectSqlAstValidator.DEFAULT_FORBIDDEN_FUNCTIONS);
    extended.add("w1_4_test_only_marker_fn");
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setForbiddenFunctions(extended);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect(
            "select w1_4_test_only_marker_fn(tenant_id) from biz.order_event where tenant_id"
                + " = :tenantId"),
        "w1_4_test_only_marker_fn");
  }
}
