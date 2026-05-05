package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 集成测试：batch.file_error_record 表（V9 迁移）。 覆盖：插入、按 tenant+file 查询、skip 标志、raw_record JSONB。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FileErrorRecordIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void shouldInsertAndQueryFileErrorRecord() {
    Long fileId = 99000L + BatchDateTimeSupport.utcEpochMillis() % 1000;

    jdbcTemplate.update(
        """
        insert into batch.file_error_record
            (tenant_id, file_id, error_code, error_message, error_stage, is_skipped)
        values (?, ?, 'PARSE_ERROR', 'invalid date format', 'PARSE', false)
        """,
        "t1",
        fileId);

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "select * from batch.file_error_record where tenant_id = ? and file_id = ?",
            "t1",
            fileId);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("error_code")).isEqualTo("PARSE_ERROR");
    assertThat(rows.get(0).get("error_stage")).isEqualTo("PARSE");
    assertThat(rows.get(0).get("is_skipped")).isEqualTo(Boolean.FALSE);
  }

  @Test
  void shouldInsertMultipleErrorsForSameFile() {
    Long fileId = 98000L + BatchDateTimeSupport.utcEpochMillis() % 1000;

    jdbcTemplate.update(
        """
        insert into batch.file_error_record
            (tenant_id, file_id, error_code, error_message, error_stage, is_skipped, record_no)
        values
            (?, ?, 'REQUIRED_FIELD', 'customerNo is blank', 'VALIDATE', false, 1),
            (?, ?, 'INVALID_FORMAT', 'creditLimit must be numeric', 'VALIDATE', true, 2)
        """,
        "t1",
        fileId,
        "t1",
        fileId);

    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.file_error_record where tenant_id = ? and file_id = ?",
            Long.class,
            "t1",
            fileId);

    assertThat(count).isEqualTo(2L);

    List<Map<String, Object>> skipped =
        jdbcTemplate.queryForList(
            "select * from batch.file_error_record where tenant_id = ? and file_id = ? and"
                + " is_skipped = true",
            "t1",
            fileId);
    assertThat(skipped).hasSize(1);
    assertThat(skipped.get(0).get("error_code")).isEqualTo("INVALID_FORMAT");
  }

  @Test
  void shouldStoreRawRecordAsJsonb() {
    Long fileId = 97000L + BatchDateTimeSupport.utcEpochMillis() % 1000;
    String rawJson = "{\"customerNo\":\"C-001\",\"amount\":\"abc\"}";

    jdbcTemplate.update(
        """
        insert into batch.file_error_record
            (tenant_id, file_id, error_code, error_stage, is_skipped, raw_record)
        values (?, ?, 'TYPE_MISMATCH', 'VALIDATE', false, ?::jsonb)
        """,
        "t1",
        fileId,
        rawJson);

    String storedRaw =
        jdbcTemplate.queryForObject(
            "select raw_record::text from batch.file_error_record where tenant_id = ? and file_id ="
                + " ?",
            String.class,
            "t1",
            fileId);

    assertThat(storedRaw).isNotBlank();
    assertThat(storedRaw).contains("C-001");
  }

  @Test
  void shouldIsolateTenantData() {
    Long fileId = 96000L + BatchDateTimeSupport.utcEpochMillis() % 1000;

    jdbcTemplate.update(
        """
        insert into batch.file_error_record (tenant_id, file_id, error_code, error_stage, is_skipped)
        values (?, ?, 'ERR', 'PARSE', false)
        """,
        "t1",
        fileId);

    jdbcTemplate.update(
        """
        insert into batch.file_error_record (tenant_id, file_id, error_code, error_stage, is_skipped)
        values (?, ?, 'ERR', 'PARSE', false)
        """,
        "t2",
        fileId);

    Long t1Count =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.file_error_record where tenant_id = 't1' and file_id = ?",
            Long.class,
            fileId);
    Long t2Count =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.file_error_record where tenant_id = 't2' and file_id = ?",
            Long.class,
            fileId);

    assertThat(t1Count).isEqualTo(1L);
    assertThat(t2Count).isEqualTo(1L);
  }

  @Test
  void shouldQueryByErrorStageIndex() {
    Long fileId = 95000L + BatchDateTimeSupport.utcEpochMillis() % 1000;

    jdbcTemplate.update(
        """
        insert into batch.file_error_record
            (tenant_id, file_id, error_code, error_stage, is_skipped)
        values
            (?, ?, 'ERR1', 'PARSE', false),
            (?, ?, 'ERR2', 'VALIDATE', false),
            (?, ?, 'ERR3', 'LOAD', false)
        """,
        "t1",
        fileId,
        "t1",
        fileId,
        "t1",
        fileId);

    Long parseErrors =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.file_error_record where tenant_id = ? and file_id = ? and"
                + " error_stage = 'PARSE'",
            Long.class,
            "t1",
            fileId);
    Long validateErrors =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.file_error_record where tenant_id = ? and file_id = ? and"
                + " error_stage = 'VALIDATE'",
            Long.class,
            "t1",
            fileId);

    assertThat(parseErrors).isEqualTo(1L);
    assertThat(validateErrors).isEqualTo(1L);
  }
}
