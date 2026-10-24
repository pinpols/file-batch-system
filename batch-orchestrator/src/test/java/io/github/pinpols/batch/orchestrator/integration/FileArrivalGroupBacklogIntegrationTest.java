package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.mapper.FileGovernanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("文件到达积压按组计数与等待时长")
class FileArrivalGroupBacklogIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private FileGovernanceMapper fileGovernanceMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @DisplayName("积压按文件组计数并测量最老等待组")
  @Test
  void shouldCountGroupsRatherThanRowsAndMeasureOldestWaitingGroup() {
    String tenantId = "it-arrival-metrics-" + UUID.randomUUID();
    String olderGroup = "older-" + UUID.randomUUID();
    String newerGroup = "newer-" + UUID.randomUUID();
    insertWaitingFile(tenantId, olderGroup, "older-a.csv", "-2 days");
    insertWaitingFile(tenantId, olderGroup, "older-b.csv", "-1 day");
    insertWaitingFile(tenantId, newerGroup, "newer-a.csv", "-1 hour");

    Map<String, Object> backlog = fileGovernanceMapper.selectArrivalGroupBacklogMetrics();

    long waitingGroupCount = ((Number) backlog.get("waiting_groups")).longValue();
    long oldestWaitingSeconds = ((Number) backlog.get("oldest_wait_seconds")).longValue();
    assertThat(waitingGroupCount).isEqualTo(2L);
    assertThat(oldestWaitingSeconds).isBetween(172_700L, 172_900L);
  }

  private void insertWaitingFile(
      String tenantId, String groupCode, String fileName, String createdAtOffset) {
    String metadata = """
        {"fileGroupCode":"%s","waitFileGroupMode":"ALL_OF",
         "requiredFileSet":"a.csv,b.csv","arrivalState":"WAITING_ARRIVAL"}
        """.formatted(groupCode);
    final String insertWaitingFileSql = """
    insert into batch.file_record (
    tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
    source_type, file_status, biz_date, metadata_json, created_at, updated_at
    ) values (
    ?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'SYSTEM', 'RECEIVED', ?, ?::jsonb,
    current_timestamp + ?::interval, current_timestamp + ?::interval
    )
    """;
    String storagePath = "arrival-metrics/" + groupCode + "/" + fileName;
    LocalDate businessDate = LocalDate.of(2026, 10, 14);
        jdbcTemplate.update(
        insertWaitingFileSql,
        tenantId,
        fileName,
        storagePath,
        businessDate,
        metadata,
        createdAtOffset,
        createdAtOffset);
  }
}
