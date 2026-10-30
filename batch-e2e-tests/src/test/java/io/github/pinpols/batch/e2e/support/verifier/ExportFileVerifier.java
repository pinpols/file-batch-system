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
 * 用于 Export 流程 E2E 测试的内容校验器。
 *
 * <p>Asserts the "状态 + 产物 + 审计" triple-check for export jobs:
 *
 * <ol>
 *   <li><b>File record</b>: the job's output {@code file_record} has a {@code storage_path}.
 *   <li><b>Amount rollup</b>: {@code settlement_batch.total_amount} meets the expected minimum
 *   <li><b>Object content</b>: file read through the active test backend is non-empty and contains
 *       expected content snippets.
 * </ol>
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

  private void verifyFileRecord() {
    final String selectStoragePathSql = """
            select fr.storage_path, fr.storage_bucket, fr.file_status, fr.file_size_bytes
            from batch.job_instance ji
            join batch.pipeline_instance pi on pi.tenant_id = ji.tenant_id
                and pi.related_job_instance_id = ji.id
            join batch.file_record fr on fr.tenant_id = pi.tenant_id and fr.id = pi.file_id
            where ji.tenant_id = ? and ji.dedup_key = ?
              and fr.file_category = 'OUTPUT'
            order by fr.id desc
            limit 1
            """;

    List<Map<String, Object>> fileRecords =
        platformJdbc.queryForList(selectStoragePathSql, tenantId, dedupKey);

    assertThat(fileRecords)
        .as("export: output file record for this job must exist")
        .isNotEmpty();

    Map<String, Object> fileRecord = fileRecords.get(0);
    assertThat(fileRecord.get("storage_path"))
        .as("export: file_record.storage_path must be set")
        .isNotNull();

    if (!expectedContentSnippets.isEmpty() || expectedMinFileRows > 0) {
      String storagePath = String.valueOf(fileRecord.get("storage_path"));
      String bucket = (String) fileRecord.get("storage_bucket");
      assertObjectContent(storagePath, bucket == null ? s3Bucket : bucket);
    }
  }

  private void verifySettlementAmount() {
    final String selectTotalAmountSql = """
        select total_amount from biz.settlement_batch where tenant_id = ? and batch_no = ?
    """;

    BigDecimal totalAmount =
        businessJdbc.queryForObject(selectTotalAmountSql, BigDecimal.class, tenantId, batchNo);
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

  private void assertObjectContent(String storagePath, String bucket) {
    assertThat(bucket)
        .as("export: storage bucket is required for content verification")
        .isNotBlank();
    try {
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
    } catch (Exception ex) {
      throw new AssertionError("export: failed to read output object " + storagePath, ex);
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
