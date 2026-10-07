package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.AccountRow;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("用户批量开户真实存储: PostgreSQL 原子提交与 Redis 预览并发控制")
class ConsoleUserBatchProvisioningIntegrationTest extends AbstractIntegrationTest {

  private static final String PREVIEW_PREFIX = "console:user-batch:preview:";
  private static final String ROLLBACK_CONSTRAINT = "ck_test_user_batch_rollback";

  private final ConsoleUserBatchProvisioningService service;
  private final JdbcTemplate jdbcTemplate;
  private final StringRedisTemplate redisTemplate;

  private final List<String> previewTokens = new ArrayList<>();
  private String tenantId;
  private String usernamePrefix;

  @Autowired
  ConsoleUserBatchProvisioningIntegrationTest(
      ConsoleUserBatchProvisioningService service,
      JdbcTemplate jdbcTemplate,
      StringRedisTemplate redisTemplate) {
    this.service = service;
    this.jdbcTemplate = jdbcTemplate;
    this.redisTemplate = redisTemplate;
  }

  @BeforeEach
  void setUp() {
    String suffix = Long.toUnsignedString(System.nanoTime(), 36);
    tenantId = "bulk-it-" + suffix;
    usernamePrefix = "bulk-it-user-" + suffix;
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status, created_by) values (?, ?, 'ACTIVE', 'test')",
        tenantId,
        tenantId);
    authenticateTenantAdmin();
  }

  @AfterEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
    jdbcTemplate.execute(
        "alter table batch.console_user_account drop constraint if exists " + ROLLBACK_CONSTRAINT);
    jdbcTemplate.update(
        "delete from batch.console_user_batch_operation where tenant_ids = ?", tenantId);
    jdbcTemplate.update(
        "delete from batch.console_user_account where username like ?", usernamePrefix + "%");
    jdbcTemplate.update("delete from batch.tenant where tenant_id = ?", tenantId);
    previewTokens.forEach(token -> redisTemplate.delete(PREVIEW_PREFIX + token));
  }

  @Test
  @DisplayName("成功提交: 账号和操作记录同事务落库, Redis 预览在提交后删除")
  void shouldPersistAccountsAndOperationAtomically_whenApplySucceeds() throws IOException {
    String username = usernamePrefix + "-success";
    var preview = preview(workbook(username));
    UUID requestId = UUID.randomUUID();

    var result = service.apply(preview.previewToken(), preview.version(), requestId);

    assertThat(result.accountCount()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.console_user_account where username = ? and must_change_password",
            Integer.class,
            username))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.console_user_batch_operation where request_id = ?",
            Integer.class,
            requestId))
        .isEqualTo(1);
    assertThat(redisTemplate.hasKey(PREVIEW_PREFIX + preview.previewToken())).isFalse();
  }

  @Test
  @DisplayName("数据库中途失败: 已创建账号和操作记录整体回滚, Redis 预览恢复为可编辑")
  void shouldRollbackAccountsAndRestorePreview_whenSecondInsertFails() throws IOException {
    String first = usernamePrefix + "-first";
    String rejected = usernamePrefix + "-rejected";
    var preview = preview(workbook(first, rejected));
    jdbcTemplate.execute("alter table batch.console_user_account add constraint "
        + ROLLBACK_CONSTRAINT + " check (username <> '" + rejected + "')");
    UUID requestId = UUID.randomUUID();

    assertThatThrownBy(() -> service.apply(preview.previewToken(), preview.version(), requestId))
        .isInstanceOf(RuntimeException.class);

    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.console_user_account where username in (?, ?)",
            Integer.class,
            first,
            rejected))
        .isZero();
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.console_user_batch_operation where tenant_ids = ?",
            Integer.class,
            tenantId))
        .isZero();
    var edited = new AccountRow(2, tenantId, first, "Retry", ConsoleRoles.TENANT_USER);
    assertThat(service.patch(preview.previewToken(), preview.version(), edited).version())
        .isEqualTo(preview.version() + 1);
  }

  @Test
  @DisplayName("同一预览并发提交: Redis CAS 只允许一个请求进入数据库事务")
  void shouldAllowExactlyOneApply_whenPreviewSubmittedConcurrently() throws Exception {
    String username = usernamePrefix + "-concurrent";
    var preview = preview(workbook(username));
    Callable<Boolean> apply = () -> {
      authenticateTenantAdmin();
      try {
        service.apply(preview.previewToken(), preview.version(), UUID.randomUUID());
        return true;
      } catch (BizException conflict) {
        return false;
      } finally {
        SecurityContextHolder.clearContext();
      }
    };

    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> first = executor.submit(apply);
      Future<Boolean> second = executor.submit(apply);
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
    }
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.console_user_account where username = ?",
            Integer.class,
            username))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.console_user_batch_operation where tenant_ids = ?",
            Integer.class,
            tenantId))
        .isEqualTo(1);
  }

  private ConsoleUserBatchProvisioningService.Preview preview(MockMultipartFile workbook)
      throws IOException {
    var preview = service.preview(workbook);
    previewTokens.add(preview.previewToken());
    assertThat(preview.issues()).isEmpty();
    return preview;
  }

  private MockMultipartFile workbook(String... usernames) throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("Accounts");
      var header = sheet.createRow(0);
      List<String> columns = List.of("tenantId", "username", "displayName", "role");
      for (int index = 0; index < columns.size(); index++) {
        header.createCell(index).setCellValue(columns.get(index));
      }
      for (int index = 0; index < usernames.length; index++) {
        var row = sheet.createRow(index + 1);
        row.createCell(0).setCellValue(tenantId);
        row.createCell(1).setCellValue(usernames[index]);
        row.createCell(2).setCellValue("Batch IT " + index);
        row.createCell(3).setCellValue(ConsoleRoles.TENANT_USER);
      }
      workbook.write(output);
      return new MockMultipartFile(
          "file",
          "accounts.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          output.toByteArray());
    }
  }

  private void authenticateTenantAdmin() {
    var principal =
        new ConsolePrincipal("operator-" + tenantId, tenantId, Set.of(ConsoleRoles.TENANT_ADMIN));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));
  }
}
