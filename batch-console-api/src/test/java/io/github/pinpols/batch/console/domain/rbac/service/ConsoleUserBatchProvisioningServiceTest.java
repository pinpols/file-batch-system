package io.github.pinpols.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleUserAccountResponse;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserBatchOperationEntity;
import io.github.pinpols.batch.console.domain.rbac.infrastructure.ConsoleUserBatchProvisioningStore;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserBatchOperationMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantMapper;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.AccountRow;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.RowIssue;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("用户批量开通服务: 预览校验、并发版本控制与事务回滚后的快照恢复")
class ConsoleUserBatchProvisioningServiceTest {

  private ConsoleUserAccountService accountService;
  private ConsoleUserAccountMapper accountMapper;
  private ConsoleUserBatchOperationMapper operationMapper;
  private TenantMapper tenantMapper;
  private ConsoleUserBatchProvisioningStore store;
  private ConsoleUserBatchProvisioningService service;
  private final Map<String, String> stored = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    accountService = mock(ConsoleUserAccountService.class);
    accountMapper = mock(ConsoleUserAccountMapper.class);
    operationMapper = mock(ConsoleUserBatchOperationMapper.class);
    tenantMapper = mock(TenantMapper.class);
    store = mock(ConsoleUserBatchProvisioningStore.class);
    doAnswer(invocation -> {
          stored.put(invocation.getArgument(0), invocation.getArgument(1));
          return null;
        })
        .when(store)
        .savePreview(anyString(), anyString(), any(Duration.class));
    when(store.loadPreview(anyString()))
        .thenAnswer(invocation -> stored.get(invocation.getArgument(0)));
    when(store.replacePreview(anyString(), anyString(), anyString(), any(Duration.class)))
        .thenAnswer(invocation -> stored.replace(
            invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
    doAnswer(invocation -> {
          stored.remove(invocation.getArgument(0), invocation.getArgument(1));
          return null;
        })
        .when(store)
        .deletePreview(anyString(), anyString());
    when(accountMapper.selectByUsername(anyString())).thenReturn(null);
    service = new ConsoleUserBatchProvisioningService(
        accountService, accountMapper, operationMapper, tenantMapper, store, new ObjectMapper());
    asTenantAdmin("ta");
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("租户管理员预览时租户被覆盖为自身租户, 平台角色所在行记为角色非法")
  void shouldOverrideTenantAndRejectPlatformRole_whenTenantAdminPreviews() throws IOException {
    var preview = service.preview(workbook("tb", "alice", ConsoleRoles.ADMIN));
    assertThat(preview.rows()).extracting(AccountRow::tenantId).containsExactly("ta");
    assertThat(preview.issues()).extracting(RowIssue::errorCode).containsExactly("INVALID_ROLE");
    verify(accountService, never()).createProvisioned(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("同一文件内用户名仅大小写不同时记为重复行")
  void shouldReportDuplicate_whenUsernamesDifferOnlyByCase() throws IOException {
    when(tenantMapper.selectByTenantId("ta")).thenReturn(Map.of("status", "ACTIVE"));
    var preview = service.preview(
        workbook("ta", "Alice", ConsoleRoles.TENANT_USER, "ta", "alice", ConsoleRoles.TENANT_USER));
    assertThat(preview.totalRows()).isEqualTo(2);
    assertThat(preview.issues())
        .extracting(RowIssue::errorCode)
        .containsExactly("DUPLICATE_IN_FILE");
  }

  @Test
  @DisplayName("版本已变更或操作者跨租户时补丁被拒")
  void shouldRejectPatch_whenVersionChangedOrOperatorForeign() throws IOException {
    var preview = service.preview(workbook("ta", "alice", ConsoleRoles.TENANT_USER));
    String token = preview.previewToken();
    AccountRow tenantRow = new AccountRow(2, "ta", "alice", "", ConsoleRoles.TENANT_USER);
    assertThatThrownBy(() -> service.patch(token, 2, tenantRow)).isInstanceOf(BizException.class);
    asTenantAdmin("tb");
    AccountRow foreignRow = new AccountRow(2, "tb", "alice", "", ConsoleRoles.TENANT_USER);
    assertThatThrownBy(() -> service.patch(token, 1, foreignRow)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("提交后逐个创建账号, 明文初始口令只返回一次且不落存储")
  void shouldCreateAllAccountsAndReturnCredentialsOnce_whenApplySucceeds() throws IOException {
    when(tenantMapper.selectByTenantId("ta")).thenReturn(Map.of("status", "ACTIVE"));
    when(accountService.createProvisioned(any(), any(), any(), any(), any()))
        .thenReturn(new ConsoleUserAccountResponse(
            42L, "ta", "alice", "", ConsoleRoles.TENANT_USER, true, null, null));
    var preview = service.preview(workbook("ta", "alice", ConsoleRoles.TENANT_USER));
    UUID requestId = UUID.randomUUID();

    var result = service.apply(preview.previewToken(), preview.version(), requestId);

    assertThat(result.accountCount()).isEqualTo(1);
    assertThat(result.credentials()).hasSize(1);
    assertThat(result.credentials().get(0).initialPassword()).hasSize(32);
    assertThat(result.toString()).doesNotContain(result.credentials().get(0).initialPassword());
    verify(accountService).createProvisioned(any(), any(), any(), any(), any());
    assertThat(stored.values())
        .allSatisfy(json -> assertThat(json).doesNotContain("initialPassword"));
  }

  @Test
  @DisplayName("同一版本并发修改只有一个请求成功, 版本号只递增一次")
  void shouldAllowExactlyOneWinner_whenEditingSameVersionConcurrently() throws Exception {
    when(tenantMapper.selectByTenantId("ta")).thenReturn(Map.of("status", "ACTIVE"));
    var preview = service.preview(workbook("ta", "alice", ConsoleRoles.TENANT_USER));
    CyclicBarrier barrier = new CyclicBarrier(2);
    when(store.loadPreview(anyString())).thenAnswer(invocation -> {
      String snapshot = stored.get(invocation.getArgument(0));
      barrier.await(3, TimeUnit.SECONDS);
      return snapshot;
    });
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> patchAsOperator(preview.previewToken(), "First"));
      var second = executor.submit(() -> patchAsOperator(preview.previewToken(), "Second"));
      assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(new ObjectMapper()
              .readTree(stored.values().iterator().next())
              .get("version")
              .asInt())
          .isEqualTo(2);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }
  }

  private boolean patchAsOperator(String token, String displayName) {
    asTenantAdmin("ta");
    try {
      service.patch(
          token, 1, new AccountRow(2, "ta", "alice", displayName, ConsoleRoles.TENANT_USER));
      return true;
    } catch (BizException conflict) {
      return false;
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  @DisplayName("提交期间冻结快照, 事务回滚后恢复为可编辑并递增版本")
  void shouldFreezeSnapshotThenRestore_whenApplyTransactionRollsBack() throws Exception {
    when(tenantMapper.selectByTenantId("ta")).thenReturn(Map.of("status", "ACTIVE"));
    var preview = service.preview(workbook("ta", "alice", ConsoleRoles.TENANT_USER));
    when(accountService.createProvisioned(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("injected database failure"));
    var replacement = new AccountRow(2, "ta", "alice", "Edited", ConsoleRoles.TENANT_USER);
    TransactionSynchronizationManager.initSynchronization();
    try {
      String token = preview.previewToken();
      UUID requestId = UUID.randomUUID();
      assertThatThrownBy(() -> service.apply(token, 1, requestId))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> service.patch(preview.previewToken(), 1, replacement))
          .isInstanceOf(BizException.class)
          .hasMessageContaining("being applied");
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
      assertThat(service.patch(preview.previewToken(), 1, replacement).version())
          .isEqualTo(2);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @ParameterizedTest
  @DisplayName("事务已提交或状态未知时不恢复可编辑, 快照保持提交中状态")
  @ValueSource(
      ints = {TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_UNKNOWN
      })
  void shouldKeepPreviewLocked_whenTransactionCompletedOrUnknown(int status) throws IOException {
    when(tenantMapper.selectByTenantId("ta")).thenReturn(Map.of("status", "ACTIVE"));
    when(accountService.createProvisioned(any(), any(), any(), any(), any()))
        .thenReturn(new ConsoleUserAccountResponse(
            42L, "ta", "alice", "", ConsoleRoles.TENANT_USER, true, null, null));
    var preview = service.preview(workbook("ta", "alice", ConsoleRoles.TENANT_USER));
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.apply(preview.previewToken(), 1, UUID.randomUUID());
      assertThat(stored.values()).allMatch(value -> value.startsWith("APPLYING:"));
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(sync -> sync.afterCompletion(status));
      if (status == TransactionSynchronization.STATUS_COMMITTED) {
        assertThat(stored).isEmpty();
      } else {
        assertThat(stored).hasSize(1);
        assertThat(stored.values()).allMatch(value -> value.startsWith("APPLYING:"));
      }
      var replacement = new AccountRow(2, "ta", "alice", "Edited", ConsoleRoles.TENANT_USER);
      assertThatThrownBy(() -> service.patch(preview.previewToken(), 1, replacement))
          .isInstanceOf(BizException.class);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @ParameterizedTest
  @DisplayName("完成回调只记录请求标识, 不记录预览令牌与存储细节")
  @ValueSource(
      ints = {TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_UNKNOWN
      })
  void shouldLogRequestIdWithoutTokenOrStorageDetails_whenCompletionRuns(int status)
      throws IOException {
    when(tenantMapper.selectByTenantId("ta")).thenReturn(Map.of("status", "ACTIVE"));
    when(accountService.createProvisioned(any(), any(), any(), any(), any()))
        .thenReturn(new ConsoleUserAccountResponse(
            42L, "ta", "alice", "", ConsoleRoles.TENANT_USER, true, null, null));
    var preview = service.preview(workbook("ta", "alice", ConsoleRoles.TENANT_USER));
    doThrow(new DataAccessResourceFailureException(
            "private-storage-details " + preview.previewToken()))
        .when(store)
        .deletePreview(anyString(), anyString());
    UUID requestId = UUID.randomUUID();
    Logger logger = (Logger) LoggerFactory.getLogger(ConsoleUserBatchProvisioningService.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.apply(preview.previewToken(), 1, requestId);
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(sync -> sync.afterCompletion(status));
      assertThat(appender.list).hasSize(1);
      var event = appender.list.getFirst();
      assertThat(event.getFormattedMessage())
          .contains(requestId.toString())
          .doesNotContain(preview.previewToken(), "private-storage-details");
      assertThat(event.getThrowableProxy()).isNull();
      assertThat(stored.values()).allMatch(value -> value.startsWith("APPLYING:"));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("租户管理员查询操作记录时始终收敛到自身租户")
  void shouldScopeLookupToOwnTenant_whenTenantAdminQueries() {
    UUID operationId = UUID.randomUUID();
    ConsoleUserBatchOperationEntity row = operation(operationId, UUID.randomUUID(), "ta,tb");
    when(operationMapper.selectByOperationId(eq(operationId), anyString(), eq("ta")))
        .thenReturn(row);

    var result = service.operation(operationId, "tb");

    assertThat(result.tenantIds()).isEqualTo("ta,tb");
    verify(operationMapper).selectByOperationId(operationId, "operator-ta", "ta");
  }

  @Test
  @DisplayName("平台管理员查询操作记录时可按目标租户筛选")
  void shouldFilterByTargetTenant_whenPlatformAdminQueries() {
    asAdmin();
    UUID requestId = UUID.randomUUID();
    ConsoleUserBatchOperationEntity row = operation(UUID.randomUUID(), requestId, "ta,tb");
    when(operationMapper.selectByRequestId(eq(requestId), eq("admin"), eq("tb")))
        .thenReturn(row);

    var result = service.findByRequestId(requestId, "tb");

    assertThat(result.requestId()).isEqualTo(requestId);
    verify(operationMapper).selectByRequestId(requestId, "admin", "tb");
  }

  @Test
  @DisplayName("目标租户为空白时筛选条件视为未传, 查询返回空")
  void shouldKeepFilterOptional_whenTargetTenantBlank() {
    asAdmin();
    UUID requestId = UUID.randomUUID();
    when(operationMapper.selectByRequestId(eq(requestId), eq("admin"), nullable(String.class)))
        .thenReturn(null);

    assertThat(service.findByRequestId(requestId, " ")).isNull();

    verify(operationMapper).selectByRequestId(eq(requestId), eq("admin"), nullable(String.class));
  }

  private static MockMultipartFile workbook(String... values) throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("Accounts");
      var header = sheet.createRow(0);
      String[] columns = {"tenantId", "username", "displayName", "role"};
      for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
      for (int i = 0; i < values.length; i += 3) {
        var row = sheet.createRow(1 + i / 3);
        row.createCell(0).setCellValue(values[i]);
        row.createCell(1).setCellValue(values[i + 1]);
        row.createCell(2).setCellValue("");
        row.createCell(3).setCellValue(values[i + 2]);
      }
      workbook.write(out);
      return new MockMultipartFile(
          "file",
          "accounts.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          out.toByteArray());
    }
  }

  private void asTenantAdmin(String tenantId) {
    var principal =
        new ConsolePrincipal("operator-" + tenantId, tenantId, Set.of(ConsoleRoles.TENANT_ADMIN));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));
  }

  private void asAdmin() {
    var principal = new ConsolePrincipal("admin", "system", Set.of(ConsoleRoles.ADMIN));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));
  }

  private static ConsoleUserBatchOperationEntity operation(
      UUID operationId, UUID requestId, String tenantIds) {
    ConsoleUserBatchOperationEntity row = new ConsoleUserBatchOperationEntity();
    row.setOperationId(operationId);
    row.setRequestId(requestId);
    row.setTenantIds(tenantIds);
    row.setAccountCount(2);
    row.setCreatedAt(OffsetDateTime.parse("2026-10-03T00:00:00Z"));
    return row;
  }
}
