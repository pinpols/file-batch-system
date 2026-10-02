package io.github.pinpols.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleUserAccountResponse;
import io.github.pinpols.batch.console.domain.rbac.infrastructure.ConsoleUserBatchProvisioningStore;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantMapper;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.AccountRow;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.RowIssue;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ConsoleUserBatchProvisioningServiceTest {

  private ConsoleUserAccountService accountService;
  private ConsoleUserAccountMapper accountMapper;
  private TenantMapper tenantMapper;
  private ConsoleUserBatchProvisioningStore store;
  private ConsoleUserBatchProvisioningService service;
  private final Map<String, String> stored = new HashMap<>();

  @BeforeEach
  void setUp() {
    accountService = mock(ConsoleUserAccountService.class);
    accountMapper = mock(ConsoleUserAccountMapper.class);
    tenantMapper = mock(TenantMapper.class);
    store = mock(ConsoleUserBatchProvisioningStore.class);
    org.mockito.Mockito.doAnswer(invocation -> {
          stored.put(invocation.getArgument(0), invocation.getArgument(1));
          return null;
        })
        .when(store)
        .savePreview(anyString(), anyString(), any(Duration.class));
    when(store.loadPreview(anyString()))
        .thenAnswer(invocation -> stored.get(invocation.getArgument(0)));
    when(accountMapper.selectByUsername(anyString())).thenReturn(null);
    service = new ConsoleUserBatchProvisioningService(
        accountService, accountMapper, tenantMapper, store, new ObjectMapper());
    asTenantAdmin("ta");
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void tenantAdminPreviewOverridesTenantAndRejectsPlatformRole() throws IOException {
    var preview = service.preview(workbook("tb", "alice", ConsoleRoles.ADMIN));
    assertThat(preview.rows()).extracting(AccountRow::tenantId).containsExactly("ta");
    assertThat(preview.issues()).extracting(RowIssue::errorCode).containsExactly("INVALID_ROLE");
    verify(accountService, never()).createProvisioned(any(), any(), any(), any(), any());
  }

  @Test
  void duplicateUsernamesUseCaseInsensitiveLoginSemantics() throws IOException {
    when(tenantMapper.selectByTenantId("ta")).thenReturn(Map.of("status", "ACTIVE"));
    var preview = service.preview(
        workbook("ta", "Alice", ConsoleRoles.TENANT_USER, "ta", "alice", ConsoleRoles.TENANT_USER));
    assertThat(preview.totalRows()).isEqualTo(2);
    assertThat(preview.issues())
        .extracting(RowIssue::errorCode)
        .containsExactly("DUPLICATE_IN_FILE");
  }

  @Test
  void rejectsChangedVersionAndForeignOperator() throws IOException {
    var preview = service.preview(workbook("ta", "alice", ConsoleRoles.TENANT_USER));
    String token = preview.previewToken();
    AccountRow tenantRow = new AccountRow(2, "ta", "alice", "", ConsoleRoles.TENANT_USER);
    assertThatThrownBy(() -> service.patch(token, 2, tenantRow)).isInstanceOf(BizException.class);
    asTenantAdmin("tb");
    AccountRow foreignRow = new AccountRow(2, "tb", "alice", "", ConsoleRoles.TENANT_USER);
    assertThatThrownBy(() -> service.patch(token, 1, foreignRow)).isInstanceOf(BizException.class);
  }

  @Test
  void applyCreatesAllAccountsAndReturnsCredentialsOnlyOnce() throws IOException {
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
}
