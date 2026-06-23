package io.github.pinpols.batch.worker.processes.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class SqlTransformComputeSqlValidatorTest {

  /**
   * 用户 SQL 校验失败统一为 {@link BizException}(INVALID_ARGUMENT + error.process.sql_validation), 详情在
   * messageArgs[0]。这样上层 catch(BizException) 归类为 BUSINESS_ERROR(确定性失败,不触发重试风暴), 而非裸
   * IllegalArgumentException 落到 catch(Exception) 被误判 INFRA_ERROR。
   */
  private static void assertRejected(ThrowingCallable call, String detailSubstring) {
    assertThatThrownBy(call)
        .isInstanceOf(BizException.class)
        .satisfies(
            e -> {
              BizException be = (BizException) e;
              assertThat(be.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT);
              if (detailSubstring != null) {
                assertThat(be.getMessageArgs()[0].toString()).contains(detailSubstring);
              }
            });
  }

  @Test
  void validateSelect_allowsSelectFromAllowlistedSchema() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    String sql =
        validator.validateSelect(
            "select tenant_id, account_id from biz.order_event where tenant_id = :tenantId");

    assertThat(sql).contains("biz.order_event");
  }

  @Test
  void validateSelect_rejectsDml() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    assertRejected(
        () -> validator.validateSelect("delete from biz.order_event"), "only allows SELECT");
  }

  @Test
  void validateSelect_rejectsDisallowedSchema() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () ->
            validator.validateSelect(
                "select id from pg_catalog.pg_tables where schemaname = 'biz'"),
        "disallowed schema");
  }

  @Test
  void validateUserCheckSelect_rejectsReadingNonStagingTables() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    assertRejected(
        () ->
            validator.validateUserCheckSelect(
                "select true AS pass, 'ok' AS message from biz.order_event"),
        "validation SQL may only read batch.process_staging");
  }

  @Test
  void validateSelect_rejectsCtasCreateTableAsSelect() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    // CTAS 被 JSqlParser 解析为 CreateTable, instanceof Select 检查直接拦,但要有显式守护
    assertRejected(
        () ->
            validator.validateSelect(
                "create table biz.foo as select tenant_id from biz.order_event"),
        "only allows SELECT");
  }

  @Test
  void validateSelect_rejectsSetSearchPath() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    assertRejected(() -> validator.validateSelect("set search_path = public, biz"), null);
  }

  @Test
  void validateSelect_rejectsDblinkFunction() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () -> validator.validateSelect("select c from dblink('host=evil', 'select 1') as t(c int)"),
        "forbidden function 'dblink'");
  }

  @Test
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
  void validateSelect_rejectsForbiddenFunctionNestedInExpression() {
    // AST 遍历应深入嵌套表达式 / 函数参数,不止顶层 select item。
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () ->
            validator.validateSelect(
                "select upper(coalesce(pg_terminate_backend(pid), 'x')) from biz.t"),
        "forbidden function 'pg_terminate_backend'");
  }

  @Test
  void validateSelect_rejectsPgTerminateBackend() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () ->
            validator.validateSelect(
                "select pg_terminate_backend(pid) from biz.fake where tenant_id = :tenantId"),
        "forbidden function 'pg_terminate_backend'");
  }

  @Test
  void validateSelect_requireLimit_rejectsUnboundedQuery() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setRequireLimit(true);
    security.setMaxLimitRows(10_000L);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () ->
            validator.validateSelect(
                "select tenant_id from biz.order_event where tenant_id = :tenantId"),
        "LIMIT");
  }

  @Test
  void validateSelect_requireLimit_rejectsOverMaxLimit() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setRequireLimit(true);
    security.setMaxLimitRows(10_000L);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertRejected(
        () ->
            validator.validateSelect(
                "select tenant_id from biz.order_event where tenant_id = :tenantId limit 50000"),
        "exceeds max");
  }

  @Test
  void validateSelect_requireLimit_acceptsLimitWithinBound() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setRequireLimit(true);
    security.setMaxLimitRows(10_000L);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    String sql =
        validator.validateSelect(
            "select tenant_id from biz.order_event where tenant_id = :tenantId limit 5000");

    assertThat(sql).contains("limit 5000");
  }

  @Test
  void validateUserCheckSelect_allowsReadingProcessStaging() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    String sql =
        validator.validateUserCheckSelect(
            "select bool_and(tenant_id = :tenantId) AS pass from batch.process_staging"
                + " where batch_key = :batchKey");

    assertThat(sql).contains("batch.process_staging");
  }
}
