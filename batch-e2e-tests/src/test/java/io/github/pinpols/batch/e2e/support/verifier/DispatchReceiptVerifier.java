package io.github.pinpols.batch.e2e.support.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Content-level verifier for Dispatch pipeline E2E tests.
 *
 * <p>Asserts the "状态 + 回执 + 审计" triple-check for dispatch jobs:
 *
 * <ol>
 *   <li><b>File status</b>: {@code file_record.file_status} has transitioned to the expected value
 *       (default: {@code DISPATCHED})
 *   <li><b>Receipt record</b>: a {@code file_dispatch_record} row exists with the expected {@code
 *       receipt_code} and matches the {@code channel_code} when configured
 *   <li><b>Audit log</b>: at least N {@code file_audit_log} entries exist for this file
 * </ol>
 *
 * <p>Unused assertions are omitted by not setting the corresponding builder fields.
 */
public final class DispatchReceiptVerifier implements E2eVerifier {

  private static final String DEFAULT_FILE_STATUS = "DISPATCHED";

  private final String tenantId;
  private final Long fileId;
  private final JdbcTemplate platformJdbc;
  private final String expectedFileStatus;
  private final String expectedReceiptCode;
  private final String expectedChannelCode;
  private final int expectedMinAuditCount;

  private DispatchReceiptVerifier(Builder builder) {
    this.tenantId = builder.tenantId;
    this.fileId = builder.fileId;
    this.platformJdbc = builder.platformJdbc;
    this.expectedFileStatus = builder.expectedFileStatus;
    this.expectedReceiptCode = builder.expectedReceiptCode;
    this.expectedChannelCode = builder.expectedChannelCode;
    this.expectedMinAuditCount = builder.expectedMinAuditCount;
  }

  @Override
  public void verify() {
    verifyFileStatus();
    verifyReceiptRecord();
    verifyAuditLog();
  }

  // ─── Individual assertions ────────────────────────────────────────────────

  private void verifyFileStatus() {
    String status =
        platformJdbc.queryForObject(
            "select file_status from batch.file_record where id = ?", String.class, fileId);
    assertThat(status)
        .as(
            "dispatch: file_record.file_status must be '%s' for file id=%d",
            expectedFileStatus, fileId)
        .isEqualTo(expectedFileStatus);
  }

  private void verifyReceiptRecord() {
    if (expectedReceiptCode == null && expectedChannelCode == null) {
      return;
    }
    List<Map<String, Object>> receipts =
        platformJdbc.queryForList(
            """
            select receipt_code, channel_code, dispatch_status
            from batch.file_dispatch_record
            where tenant_id = ? and file_id = ?
            order by id desc
            limit 1
            """,
            tenantId,
            fileId);

    assertThat(receipts)
        .as("dispatch: file_dispatch_record must exist for tenant=%s, fileId=%d", tenantId, fileId)
        .isNotEmpty();

    Map<String, Object> receipt = receipts.get(0);

    if (expectedReceiptCode != null) {
      assertThat(receipt.get("receipt_code"))
          .as("dispatch: receipt_code must be '%s'", expectedReceiptCode)
          .isEqualTo(expectedReceiptCode);
    }
    if (expectedChannelCode != null) {
      assertThat(receipt.get("channel_code"))
          .as("dispatch: channel_code must be '%s'", expectedChannelCode)
          .isEqualTo(expectedChannelCode);
    }
  }

  private void verifyAuditLog() {
    if (expectedMinAuditCount <= 0) {
      return;
    }
    Long auditCount =
        platformJdbc.queryForObject(
            "select count(*) from batch.file_audit_log where tenant_id = ? and file_id = ?",
            Long.class,
            tenantId,
            fileId);
    assertThat(auditCount)
        .as(
            "dispatch: file_audit_log must have at least %d entries for fileId=%d",
            expectedMinAuditCount, fileId)
        .isGreaterThanOrEqualTo((long) expectedMinAuditCount);
  }

  // ─── Builder ─────────────────────────────────────────────────────────────

  public static Builder forTenant(String tenantId) {
    return new Builder(tenantId);
  }

  public static final class Builder {

    private final String tenantId;
    private Long fileId;
    private JdbcTemplate platformJdbc;
    private String expectedFileStatus = DEFAULT_FILE_STATUS;
    private String expectedReceiptCode;
    private String expectedChannelCode;
    private int expectedMinAuditCount = 1;

    private Builder(String tenantId) {
      this.tenantId = tenantId;
    }

    public Builder fileId(Long fileId) {
      this.fileId = fileId;
      return this;
    }

    public Builder platformJdbc(JdbcTemplate platformJdbc) {
      this.platformJdbc = platformJdbc;
      return this;
    }

    public Builder expectedFileStatus(String status) {
      this.expectedFileStatus = status;
      return this;
    }

    public Builder expectedReceiptCode(String receiptCode) {
      this.expectedReceiptCode = receiptCode;
      return this;
    }

    public Builder expectedChannelCode(String channelCode) {
      this.expectedChannelCode = channelCode;
      return this;
    }

    public Builder expectedMinAuditCount(int count) {
      this.expectedMinAuditCount = count;
      return this;
    }

    public DispatchReceiptVerifier build() {
      return new DispatchReceiptVerifier(this);
    }
  }
}
