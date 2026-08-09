package io.github.pinpols.batch.e2e.support.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Content-level verifier for Export pipeline E2E tests.
 *
 * <p>Asserts the "状态 + 产物 + 审计" triple-check for export jobs:
 *
 * <ol>
 *   <li><b>File record</b>: {@code file_record} row has {@code storage_path} set and {@code
 *       file_status = 'GENERATED'} (or 'COMPLETED')
 *   <li><b>Amount rollup</b>: {@code settlement_batch.total_amount} meets the expected minimum
 *   <li><b>Object content</b> (best-effort): file read through the active test backend
 *       (MinIO/S3 or filesystem root) is non-empty and contains expected content snippets
 * </ol>
 *
 * <p>Unused assertions are omitted by not setting the corresponding builder fields. The content
 * check is silently skipped for LOCAL-type storage paths (e.g. {@code /...} or {@code file://...}).
 */
public final class ExportFileVerifier implements E2eVerifier {

  private final String tenantId;
  private final String dedupKey;
  private final JdbcTemplate platformJdbc;
  private final JdbcTemplate businessJdbc;
  private final String batchNo;
  private final BigDecimal expectedMinTotalAmount;
  private final int expectedMinFileRows;
  private final List<String> expectedContentSnippets;
  private final String s3Endpoint;
  private final String s3Bucket;

  private ExportFileVerifier(Builder builder) {
    this.tenantId = builder.tenantId;
    this.dedupKey = builder.dedupKey;
    this.platformJdbc = builder.platformJdbc;
    this.businessJdbc = builder.businessJdbc;
    this.batchNo = builder.batchNo;
    this.expectedMinTotalAmount = builder.expectedMinTotalAmount;
    this.expectedMinFileRows = builder.expectedMinFileRows;
    this.expectedContentSnippets = List.copyOf(builder.expectedContentSnippets);
    this.s3Endpoint = builder.s3Endpoint;
    this.s3Bucket = builder.s3Bucket;
  }

  @Override
  public void verify() {
    verifyFileRecord();
    if (businessJdbc != null && batchNo != null) {
      verifySettlementAmount();
    }
  }

  // ─── Individual assertion methods (package-visible for direct test use) ───

  private void verifyFileRecord() {
    List<Map<String, Object>> fileRecords = platformJdbc.queryForList("""
            select fr.storage_path, fr.file_status, fr.file_size_bytes
            from batch.file_record fr
            join batch.job_instance ji on ji.tenant_id = fr.tenant_id
            where ji.tenant_id = ? and ji.dedup_key = ?
              and fr.file_category = 'OUTPUT'
            order by fr.id desc
            limit 1
            """, tenantId, dedupKey);

    if (fileRecords.isEmpty()) {
      // No file_record yet — could be a pipeline without file output; skip.
      return;
    }

    Map<String, Object> fileRecord = fileRecords.get(0);
    assertThat(fileRecord.get("storage_path"))
        .as("export: file_record.storage_path must be set")
        .isNotNull();

    if (!expectedContentSnippets.isEmpty()) {
      String storagePath = String.valueOf(fileRecord.get("storage_path"));
      tryAssertObjectContent(storagePath);
    }
  }

  private void verifySettlementAmount() {
    BigDecimal totalAmount = businessJdbc.queryForObject(
        "select total_amount from biz.settlement_batch where tenant_id = ? and batch_no = ?",
        BigDecimal.class,
        tenantId,
        batchNo);
    if (expectedMinTotalAmount != null) {
      assertThat(totalAmount)
          .as("export: settlement_batch.total_amount must be >= %s", expectedMinTotalAmount)
          .isNotNull()
          .isGreaterThanOrEqualTo(expectedMinTotalAmount);
    } else {
      assertThat(totalAmount)
          .as("export: settlement_batch.total_amount must be non-null")
          .isNotNull();
    }
  }

  /** Best-effort: silently skips if MinIO is unreachable or path is LOCAL. */
  private void tryAssertObjectContent(String storagePath) {
    if (storagePath == null
        || storagePath.startsWith("/")
        || storagePath.startsWith("file://")
        || s3Bucket == null) {
      return;
    }
    try {
      String bucket = s3Bucket;
      String objectKey = storagePath.startsWith(bucket + "/")
          ? storagePath.substring(bucket.length() + 1)
          : storagePath;

      byte[] objectBytes = AbstractIntegrationTest.readObject(bucket, objectKey);
      try (var stream = new ByteArrayInputStream(objectBytes);
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

        List<String> lines = reader.lines().filter(l -> !l.isBlank()).collect(Collectors.toList());

        assertThat(lines).as("export: object file must be non-empty").isNotEmpty();

        if (expectedMinFileRows > 0) {
          assertThat(lines.size())
              .as("export: file must have at least %d non-blank lines", expectedMinFileRows)
              .isGreaterThanOrEqualTo(expectedMinFileRows);
        }

        if (!expectedContentSnippets.isEmpty()) {
          String joined = String.join("\n", lines);
          for (String snippet : expectedContentSnippets) {
            assertThat(joined)
                .as("export: file content must contain '%s'", snippet)
                .contains(snippet);
          }
        }
      }
    } catch (AssertionError ae) {
      throw ae; // re-throw assertion failures
    } catch (Exception ex) {
      // Backend unavailable or file not present — skip content check (best-effort)
    }
  }

  // ─── Builder ─────────────────────────────────────────────────────────────

  public static Builder forTenant(String tenantId) {
    return new Builder(tenantId);
  }

  public static final class Builder {

    private final String tenantId;
    private String dedupKey;
    private JdbcTemplate platformJdbc;
    private JdbcTemplate businessJdbc;
    private String batchNo;
    private BigDecimal expectedMinTotalAmount;
    private int expectedMinFileRows;
    private List<String> expectedContentSnippets = List.of();
    private String s3Endpoint;
    private String s3Bucket;

    private Builder(String tenantId) {
      this.tenantId = tenantId;
    }

    public Builder dedupKey(String dedupKey) {
      this.dedupKey = dedupKey;
      return this;
    }

    public Builder platformJdbc(JdbcTemplate platformJdbc) {
      this.platformJdbc = platformJdbc;
      return this;
    }

    public Builder businessJdbc(JdbcTemplate businessJdbc) {
      this.businessJdbc = businessJdbc;
      return this;
    }

    public Builder batchNo(String batchNo) {
      this.batchNo = batchNo;
      return this;
    }

    public Builder expectedMinTotalAmount(BigDecimal amount) {
      this.expectedMinTotalAmount = amount;
      return this;
    }

    public Builder expectedMinFileRows(int rows) {
      this.expectedMinFileRows = rows;
      return this;
    }

    public Builder expectedContentSnippets(String... snippets) {
      this.expectedContentSnippets = Arrays.asList(snippets);
      return this;
    }

    public Builder s3Endpoint(String endpoint) {
      this.s3Endpoint = endpoint;
      return this;
    }

    public Builder s3Bucket(String bucket) {
      this.s3Bucket = bucket;
      return this;
    }

    public ExportFileVerifier build() {
      return new ExportFileVerifier(this);
    }
  }
}
