package io.github.pinpols.batch.orchestrator.application.service.lineage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.LineageEvidenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LineageEvidenceServiceTest {

  private final ResultVersionMapper resultVersionMapper = mock(ResultVersionMapper.class);
  private final ResultVersionQueryService resultVersionQueryService =
      mock(ResultVersionQueryService.class);
  private final LineageEvidenceMapper lineageEvidenceMapper = mock(LineageEvidenceMapper.class);
  private final LineageEvidenceService service =
      new LineageEvidenceService(
          resultVersionMapper, resultVersionQueryService, lineageEvidenceMapper);

  @Test
  @SuppressWarnings("unchecked")
  void evidenceForResultVersionShouldAssembleHotTableChain() {
    ResultVersionEntity version = version(7L, "FILE_RECORD", "file_record:11");
    when(resultVersionMapper.selectById("ta", 7L)).thenReturn(version);
    when(lineageEvidenceMapper.selectJobInstance("ta", 101L))
        .thenReturn(Map.of("id", 101L, "job_code", "daily"));
    when(lineageEvidenceMapper.selectPipelineInstances("ta", 101L))
        .thenReturn(List.of(Map.of("id", 21L, "file_id", 11L)));
    when(lineageEvidenceMapper.selectFileRecords("ta", 101L, 11L))
        .thenReturn(List.of(Map.of("id", 11L, "file_name", "out.csv")));
    when(lineageEvidenceMapper.selectDispatchRecords("ta", 101L, List.of(11L)))
        .thenReturn(List.of(Map.of("id", 31L, "receipt_status", "SUCCESS")));

    Map<String, Object> evidence = service.evidenceForResultVersion("ta", 7L);

    assertThat((Map<String, Object>) evidence.get("resultVersion")).containsEntry("id", 7L);
    assertThat((List<Map<String, Object>>) evidence.get("fileRecords")).hasSize(1);
    assertThat((List<Map<String, Object>>) evidence.get("dispatchRecords")).hasSize(1);
    Map<String, Object> coverage = (Map<String, Object>) evidence.get("coverage");
    assertThat(coverage)
        .containsEntry("payloadFileId", 11L)
        .containsEntry("payloadFileResolved", true)
        .containsEntry("dispatchRecordCount", 1);
    assertThat((List<String>) coverage.get("knownGaps")).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void evidenceShouldExposeKnownGapsInsteadOfPretendingCompleteness() {
    ResultVersionEntity version = version(8L, "FILE_RECORD", "file_record:99");
    when(resultVersionMapper.selectById("ta", 8L)).thenReturn(version);
    when(lineageEvidenceMapper.selectPipelineInstances("ta", 101L)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectFileRecords("ta", 101L, 99L)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectDispatchRecords("ta", 101L, List.of())).thenReturn(List.of());

    Map<String, Object> evidence = service.evidenceForResultVersion("ta", 8L);

    Map<String, Object> coverage = (Map<String, Object>) evidence.get("coverage");
    assertThat((List<String>) coverage.get("knownGaps"))
        .contains(
            "job_instance not found in hot or archive tables",
            "payload_ref file_record not found in hot tables",
            "no related file_record found in hot tables; file_record has no archive mirror",
            "no dispatch receipt found in hot or archive tables");
    assertThat(coverage).containsEntry("payloadFileResolved", false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void evidenceShouldFallbackToArchiveTables() {
    ResultVersionEntity version = version(10L, "FILE_RECORD", "file_record:11");
    when(resultVersionMapper.selectById("ta", 10L)).thenReturn(null);
    when(resultVersionMapper.selectArchivedById("ta", 10L)).thenReturn(version);
    when(lineageEvidenceMapper.selectJobInstance("ta", 101L)).thenReturn(null);
    when(lineageEvidenceMapper.selectArchivedJobInstance("ta", 101L))
        .thenReturn(Map.of("id", 101L, "job_code", "daily"));
    when(lineageEvidenceMapper.selectPipelineInstances("ta", 101L)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectArchivedPipelineInstances("ta", 101L))
        .thenReturn(List.of(Map.of("id", 21L, "file_id", 11L)));
    when(lineageEvidenceMapper.selectFileRecords("ta", 101L, 11L))
        .thenReturn(List.of(Map.of("id", 11L, "file_name", "out.csv")));
    when(lineageEvidenceMapper.selectDispatchRecords("ta", 101L, List.of(11L)))
        .thenReturn(List.of());
    when(lineageEvidenceMapper.selectArchivedDispatchRecords("ta", 101L, List.of(11L)))
        .thenReturn(List.of(Map.of("id", 31L, "receipt_status", "SUCCESS")));

    Map<String, Object> evidence = service.evidenceForResultVersion("ta", 10L);

    Map<String, Object> coverage = (Map<String, Object>) evidence.get("coverage");
    assertThat(coverage).containsEntry("scope", "BFS_HOT_AND_ARCHIVE");
    Map<String, Object> sources = (Map<String, Object>) coverage.get("sources");
    assertThat(sources)
        .containsEntry("resultVersion", "ARCHIVE")
        .containsEntry("jobInstance", "ARCHIVE")
        .containsEntry("pipelineInstances", "ARCHIVE")
        .containsEntry("fileRecords", "HOT")
        .containsEntry("dispatchRecords", "ARCHIVE");
    assertThat((List<String>) coverage.get("knownGaps")).isEmpty();
  }

  @Test
  void evidenceForEffectiveShouldUseResultVersionQueryService() {
    ResultVersionEntity version = version(9L, "INLINE_JSON", null);
    when(resultVersionQueryService.findEffective("ta", "job:daily:2026-06-30"))
        .thenReturn(Optional.of(version));
    when(lineageEvidenceMapper.selectPipelineInstances("ta", 101L)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectFileRecords("ta", 101L, null)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectDispatchRecords("ta", 101L, List.of())).thenReturn(List.of());

    service.evidenceForEffective("ta", "job:daily:2026-06-30");

    verify(resultVersionQueryService).findEffective("ta", "job:daily:2026-06-30");
  }

  @Test
  void missingResultVersionShouldRaiseNotFound() {
    when(resultVersionMapper.selectById("ta", 404L)).thenReturn(null);

    assertThatThrownBy(() -> service.evidenceForResultVersion("ta", 404L))
        .isInstanceOf(BizException.class);
  }

  private static ResultVersionEntity version(Long id, String payloadStorage, String payloadRef) {
    Instant now = Instant.parse("2026-06-30T00:00:00Z");
    return ResultVersionEntity.builder()
        .id(id)
        .tenantId("ta")
        .businessKey("job:daily:2026-06-30")
        .versionNo(3)
        .jobInstanceId(101L)
        .status("EFFECTIVE")
        .effectiveAt(now)
        .payloadStorage(payloadStorage)
        .payloadRef(payloadRef)
        .generatedAt(now)
        .generatedBy("test")
        .promotionPolicy("AUTO_LATEST")
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}
