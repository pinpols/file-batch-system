package io.github.pinpols.batch.console.domain.rbac.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleUserAccountResponse;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserBatchOperationEntity;
import io.github.pinpols.batch.console.domain.rbac.infrastructure.ConsoleUserBatchProvisioningStore;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserBatchOperationMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.web.UploadFileGuard;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/** 账号批量开户：Redis 仅保存短期预览，数据库事务是 Apply 的唯一事实源。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsoleUserBatchProvisioningService {

  private static final int MAX_ROWS = 500;
  private static final long MAX_BYTES = 2L * 1024 * 1024;
  private static final Duration PREVIEW_TTL = Duration.ofMinutes(15);
  private static final String PREFIX = "console:user-batch:preview:";
  private static final List<String> HEADERS =
      List.of("tenantId", "username", "displayName", "role");
  private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{1,127}$");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ConsoleUserAccountService accountService;
  private final ConsoleUserAccountMapper accountMapper;
  private final ConsoleUserBatchOperationMapper operationMapper;
  private final TenantMapper tenantMapper;
  private final ConsoleUserBatchProvisioningStore store;
  private final ObjectMapper objectMapper;

  public record AccountRow(
      int rowNo, String tenantId, String username, String displayName, String role) {}

  public record RowIssue(int rowNo, String username, String errorCode, String message) {}

  public record Preview(
      String previewToken,
      int version,
      String sourceDigest,
      int totalRows,
      int validRows,
      List<AccountRow> rows,
      List<RowIssue> issues) {}

  public record Credential(
      long accountId, String tenantId, String username, String initialPassword) {
    @Override
    public String toString() {
      return "Credential[accountId=" + accountId + ", tenantId=" + tenantId + ", username="
          + username + ", secret=<redacted>]";
    }
  }

  public record ApplyResult(UUID operationId, int accountCount, List<Credential> credentials) {}

  public record Operation(
      UUID operationId, UUID requestId, int accountCount, String tenantIds, String createdAt) {}

  private record Session(String actor, String sourceDigest, int version, List<AccountRow> rows) {}

  private record StoredSession(Session session, String json) {}

  private static final String APPLYING_PREFIX = "APPLYING:";

  public byte[] template() {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("Accounts");
      Row header = sheet.createRow(0);
      for (int i = 0; i < HEADERS.size(); i++) {
        header.createCell(i).setCellValue(HEADERS.get(i));
      }
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to create account template", ex);
    }
  }

  public Preview preview(MultipartFile file) throws IOException {
    UploadFileGuard.requireExcel(file);
    String filename = file.getOriginalFilename();
    if (file.getSize() > MAX_BYTES
        || filename == null // empty-check: allow - Sonar 需要直接识别解引用前的空值保护。
        || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
      throw invalid("XLSX file exceeds 2 MiB or has an unsupported extension");
    }
    byte[] bytes = file.getBytes();
    List<AccountRow> rows = parse(bytes);
    if (EmptyChecks.isEmpty(rows)) throw invalid("Workbook contains no accounts");
    String token = UUID.randomUUID().toString();
    String digest = digest(bytes);
    Session session = new Session(actor().username(), digest, 1, rows);
    save(token, session);
    return validate(token, session);
  }

  public Preview patch(String token, int version, AccountRow replacement) {
    StoredSession stored = load(token);
    Session session = stored.session();
    if (version != session.version())
      throw BizException.of(ResultCode.CONFLICT, "preview version changed");
    List<AccountRow> rows = new ArrayList<>(session.rows());
    int index = -1;
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).rowNo() == replacement.rowNo()) {
        index = i;
        break;
      }
    }
    if (index < 0) throw invalid("Unknown preview row");
    rows.set(index, replacement);
    Session updated = new Session(session.actor(), session.sourceDigest(), version + 1, rows);
    if (!store.replacePreview(PREFIX + token, stored.json(), serialize(updated), PREVIEW_TTL)) {
      throw BizException.of(ResultCode.CONFLICT, "preview version changed");
    }
    return validate(token, updated);
  }

  @Transactional
  public ApplyResult apply(String token, int version, UUID requestId) {
    StoredSession stored = load(token);
    Session session = stored.session();
    if (session.version() != version)
      throw BizException.of(ResultCode.CONFLICT, "preview version changed");
    if (EmptyChecks.isNull(requestId)) throw invalid("requestId is required");
    if (EmptyChecks.isNotNull(findByRequestId(requestId, null))) {
      throw BizException.of(ResultCode.CONFLICT, "Batch already applied; query by requestId");
    }
    Preview checked = validate(token, session);
    if (EmptyChecks.isNotEmpty(checked.issues())) {
      throw BizException.of(
          ResultCode.CONFLICT, "Preview has validation errors; refresh and correct it");
    }
    String applying = APPLYING_PREFIX + UUID.randomUUID();
    if (!store.replacePreview(PREFIX + token, stored.json(), applying, PREVIEW_TTL)) {
      throw BizException.of(ResultCode.CONFLICT, "preview version changed");
    }
    boolean managedTransaction = TransactionSynchronizationManager.isSynchronizationActive();
    if (managedTransaction) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
          if (status == STATUS_UNKNOWN) {
            // 提交结果不确定时不能恢复可编辑状态，保留冻结态并通过操作记录核实。
            log.error("batch preview transaction outcome unknown: requestId={}", requestId);
            return;
          }
          finishApply(token, applying, stored.json(), status == STATUS_COMMITTED, requestId);
        }
      });
    }
    boolean succeeded = false;
    try {
      ApplyResult result = createAccounts(session, checked, requestId);
      succeeded = true;
      return result;
    } finally {
      // 单测直接调用时无事务代理；生产路径必须等真实提交/回滚后再消费或恢复预览。
      if (!managedTransaction) {
        finishApply(token, applying, stored.json(), succeeded, requestId);
      }
    }
  }

  private void finishApply(
      String token, String applying, String original, boolean committed, UUID requestId) {
    try {
      if (committed) {
        store.deletePreview(PREFIX + token, applying);
      } else {
        store.replacePreview(PREFIX + token, applying, original, PREVIEW_TTL);
      }
    } catch (DataAccessException ex) {
      // 提交记录在数据库中；Redis 清理失败时保留冻结态直至 TTL，不误报业务回滚。
      // 预览令牌及存储异常可能包含账户信息，仅记录操作关联标识和异常类型。
      log.error(
          "batch preview finalization failed: requestId={} committed={} cause={}",
          requestId,
          committed,
          ex.getClass().getSimpleName());
    }
  }

  private ApplyResult createAccounts(Session session, Preview checked, UUID requestId) {
    List<Credential> credentials = new ArrayList<>();
    Set<String> tenantIds = new HashSet<>();
    for (AccountRow row : checked.rows()) {
      String password = randomPassword();
      ConsoleUserAccountResponse created = accountService.createProvisioned(
          row.tenantId(), row.username(), password, row.displayName(), row.role());
      credentials.add(
          new Credential(created.id(), created.tenantId(), created.username(), password));
      tenantIds.add(created.tenantId());
    }
    UUID operationId = UUID.randomUUID();
    operationMapper.insertOperation(
        operationId,
        requestId,
        session.actor(),
        session.sourceDigest(),
        credentials.size(),
        String.join(",", tenantIds.stream().sorted().toList()));
    return new ApplyResult(operationId, credentials.size(), credentials);
  }

  public Operation operation(UUID operationId, String targetTenantId) {
    ConsolePrincipal principal = actor();
    ConsoleUserBatchOperationEntity found = operationMapper.selectByOperationId(
        operationId, principal.username(), effectiveTenantFilter(targetTenantId, principal));
    if (EmptyChecks.isNull(found))
      throw BizException.of(ResultCode.NOT_FOUND, "batch operation not found");
    return toOperation(found);
  }

  public Operation findByRequestId(UUID requestId, String targetTenantId) {
    ConsolePrincipal principal = actor();
    ConsoleUserBatchOperationEntity found = operationMapper.selectByRequestId(
        requestId, principal.username(), effectiveTenantFilter(targetTenantId, principal));
    return EmptyChecks.isNull(found) ? null : toOperation(found);
  }

  public Operation findByRequestId(UUID requestId) {
    return findByRequestId(requestId, null);
  }

  private static Operation toOperation(ConsoleUserBatchOperationEntity row) {
    return new Operation(
        row.getOperationId(),
        row.getRequestId(),
        row.getAccountCount(),
        row.getTenantIds(),
        row.getCreatedAt().toString());
  }

  private Preview validate(String token, Session session) {
    ConsolePrincipal principal = actor();
    Set<String> usernames = new HashSet<>();
    List<RowIssue> issues = new ArrayList<>();
    List<AccountRow> normalized = new ArrayList<>();
    for (AccountRow raw : session.rows()) {
      AccountRow row = normalize(raw, principal);
      normalized.add(row);
      String code = validateRow(row, principal, usernames);
      if (EmptyChecks.isNotNull(code))
        issues.add(new RowIssue(row.rowNo(), row.username(), code, code));
    }
    return new Preview(
        token,
        session.version(),
        session.sourceDigest(),
        normalized.size(),
        normalized.size() - issues.size(),
        normalized,
        issues);
  }

  private AccountRow normalize(AccountRow raw, ConsolePrincipal principal) {
    String tenantId = trim(raw.tenantId());
    if (!principal.authorities().contains(ConsoleRoles.ADMIN)) tenantId = principal.tenantId();
    return new AccountRow(
        raw.rowNo(),
        tenantId,
        trim(raw.username()),
        trim(raw.displayName()),
        trim(raw.role()).toUpperCase(Locale.ROOT));
  }

  private String validateRow(AccountRow row, ConsolePrincipal principal, Set<String> usernames) {
    if (!USERNAME.matcher(row.username()).matches() || row.displayName().length() > 256)
      return "INVALID_ACCOUNT";
    if (!usernames.add(row.username().toLowerCase(Locale.ROOT))) return "DUPLICATE_IN_FILE";
    if (!ConsoleRoles.ALL.contains(row.role())
        || (!principal.authorities().contains(ConsoleRoles.ADMIN)
            && !Set.of(ConsoleRoles.TENANT_ADMIN, ConsoleRoles.TENANT_USER).contains(row.role())))
      return "INVALID_ROLE";
    if (Set.of(ConsoleRoles.ADMIN, ConsoleRoles.AUDITOR).contains(row.role())) {
      if (!"system".equals(row.tenantId())) return "INVALID_TENANT";
    } else {
      Map<String, Object> tenant = tenantMapper.selectByTenantId(row.tenantId());
      if (EmptyChecks.isNull(tenant) || !"ACTIVE".equals(tenant.get("status")))
        return "TENANT_NOT_ACTIVE";
    }
    return EmptyChecks.isNull(accountMapper.selectByUsername(row.username()))
        ? null
        : "USERNAME_EXISTS";
  }

  private static String trim(String value) {
    return EmptyChecks.isNull(value) ? "" : value.trim();
  }

  private static String effectiveTenantFilter(
      String requestedTenantId, ConsolePrincipal principal) {
    if (!principal.authorities().contains(ConsoleRoles.ADMIN)) return principal.tenantId();
    String trimmed = trim(requestedTenantId);
    return EmptyChecks.isEmpty(trimmed) ? null : trimmed;
  }

  private List<AccountRow> parse(byte[] bytes) {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if (!(workbook instanceof XSSFWorkbook xssf)
          || xssf.getPackagePart()
              .getRelationshipsByType(XSSFRelation.EXTERNAL_LINKS.getRelation())
              .iterator()
              .hasNext()) {
        throw invalid("Only XLSX without external links is supported");
      }
      if (workbook.getNumberOfSheets() != 1 || !"Accounts".equals(workbook.getSheetName(0))) {
        throw invalid("Expected one Accounts sheet");
      }
      Sheet sheet = workbook.getSheetAt(0);
      if (sheet.getLastRowNum() > 5000) throw invalid("Workbook has too many rows");
      DataFormatter formatter = new DataFormatter(Locale.ROOT);
      validateHeader(sheet, formatter);
      return parseRows(sheet, formatter);
    } catch (IOException | InvalidFormatException ex) {
      throw invalid("Invalid XLSX workbook");
    }
  }

  private void validateHeader(Sheet sheet, DataFormatter formatter) {
    Row header = sheet.getRow(0);
    if (EmptyChecks.isNull(header) || header.getLastCellNum() != HEADERS.size())
      throw invalid("Invalid header");
    for (int i = 0; i < HEADERS.size(); i++) {
      if (!HEADERS.get(i).equals(text(header.getCell(i), formatter)))
        throw invalid("Invalid header");
    }
  }

  private List<AccountRow> parseRows(Sheet sheet, DataFormatter formatter) {
    List<AccountRow> rows = new ArrayList<>();
    for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (EmptyChecks.isNotNull(row)) {
        AccountRow account = parseRow(row, rowIndex + 1, formatter);
        if (EmptyChecks.isNotNull(account)) {
          if (rows.size() == MAX_ROWS) throw invalid("Maximum 500 accounts per batch");
          rows.add(account);
        }
      }
    }
    return rows;
  }

  private AccountRow parseRow(Row row, int rowNo, DataFormatter formatter) {
    if (row.getLastCellNum() > HEADERS.size()) throw invalid("Unexpected columns at row " + rowNo);
    List<String> cells = new ArrayList<>();
    for (int col = 0; col < HEADERS.size(); col++) cells.add(text(row.getCell(col), formatter));
    if (cells.stream().allMatch(String::isBlank)) return null;
    return new AccountRow(rowNo, cells.get(0), cells.get(1), cells.get(2), cells.get(3));
  }

  private String text(Cell cell, DataFormatter formatter) {
    if (EmptyChecks.isNull(cell)) return "";
    if (cell.getCellType() == CellType.FORMULA) throw invalid("Formula cells are not allowed");
    String value = formatter.formatCellValue(cell).trim();
    if (value.length() > 256) throw invalid("Cell value exceeds 256 characters");
    return value;
  }

  private void save(String token, Session session) {
    store.savePreview(PREFIX + token, serialize(session), PREVIEW_TTL);
  }

  private String serialize(Session session) {
    try {
      return objectMapper.writeValueAsString(session);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize batch preview", ex);
    }
  }

  private StoredSession load(String token) {
    String json = store.loadPreview(PREFIX + token);
    if (EmptyChecks.isNull(json)) throw BizException.of(ResultCode.NOT_FOUND, "Preview expired");
    if (json.startsWith(APPLYING_PREFIX)) {
      throw BizException.of(ResultCode.CONFLICT, "Preview is being applied");
    }
    try {
      Session session = objectMapper.readValue(json, Session.class);
      if (!actor().username().equals(session.actor())) {
        throw BizException.of(ResultCode.FORBIDDEN, "Preview belongs to another operator");
      }
      return new StoredSession(session, json);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to read batch preview", ex);
    }
  }

  private ConsolePrincipal actor() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null // empty-check: allow - Sonar 需要直接识别认证对象的空值保护。
        || !(auth.getPrincipal() instanceof ConsolePrincipal principal)) {
      throw BizException.of(ResultCode.UNAUTHORIZED, "Authentication required");
    }
    return principal;
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String randomPassword() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static BizException invalid(String detail) {
    return BizException.of(
        ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey(), detail);
  }
}
