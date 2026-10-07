package io.github.pinpols.batch.orchestrator.application.service.lineage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.FileReceiptStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.DispatchRecord;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.FileRecord;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.JobInstance;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.PipelineInstance;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.LineageEvidenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("血缘证据服务: 热表与归档表回退, 覆盖缺口与生效版本查询口径")
class LineageEvidenceServiceTest {

  private final ResultVersionMapper resultVersionMapper = mock(ResultVersionMapper.class);
  private final ResultVersionQueryService resultVersionQueryService =
      mock(ResultVersionQueryService.class);
  private final LineageEvidenceMapper lineageEvidenceMapper = mock(LineageEvidenceMapper.class);
  private final LineageEvidenceService service = new LineageEvidenceService(
      resultVersionMapper, resultVersionQueryService, lineageEvidenceMapper);

  @Test
  @DisplayName("按结果版本收集时装配作业实例, 流水线, 文件与派发记录, 且无覆盖缺口")
  void shouldAssembleHotTableChain_whenQueryingByResultVersion() {
    ResultVersionEntity version = version(7L, "FILE_RECORD", "file_record:11");
    when(resultVersionMapper.selectById("ta", 7L)).thenReturn(version);
    when(lineageEvidenceMapper.selectJobInstance("ta", 101L))
        .thenReturn(JobInstance.builder().id(101L).jobCode("daily").build());
    when(lineageEvidenceMapper.selectPipelineInstances("ta", 101L))
        .thenReturn(List.of(PipelineInstance.builder().id(21L).fileId(11L).build()));
    when(lineageEvidenceMapper.selectFileRecords("ta", 101L, 11L))
        .thenReturn(List.of(FileRecord.builder().id(11L).fileName("out.csv").build()));
    when(lineageEvidenceMapper.selectDispatchRecords("ta", 101L, List.of(11L)))
        .thenReturn(List.of(DispatchRecord.builder()
            .id(31L)
            .receiptStatus(FileReceiptStatus.SUCCESS.code())
            .build()));

    LineageEvidenceResponse evidence = service.evidenceForResultVersion("ta", 7L);

    assertThat(evidence.resultVersion().id()).isEqualTo(7L);
    assertThat(evidence.fileRecords()).hasSize(1);
    assertThat(evidence.dispatchRecords()).hasSize(1);
    LineageEvidenceResponse.LineageCoverage coverage = evidence.coverage();
    assertThat(coverage.payloadFileId()).isEqualTo(11L);
    assertThat(coverage.payloadFileResolved()).isTrue();
    assertThat(coverage.dispatchRecordCount()).isEqualTo(1);
    assertThat(coverage.knownGaps()).isEmpty();
  }

  @Test
  @DisplayName("载荷文件无法解析时如实给出覆盖缺口, 不伪装完整")
  void shouldExposeKnownGaps_whenPayloadFileUnresolved() {
    ResultVersionEntity version = version(8L, "FILE_RECORD", "file_record:99");
    when(resultVersionMapper.selectById("ta", 8L)).thenReturn(version);
    when(lineageEvidenceMapper.selectPipelineInstances("ta", 101L)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectFileRecords("ta", 101L, 99L)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectArchivedFileRecords("ta", 101L, 99L)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectDispatchRecords("ta", 101L, List.of())).thenReturn(List.of());

    LineageEvidenceResponse evidence = service.evidenceForResultVersion("ta", 8L);

    LineageEvidenceResponse.LineageCoverage coverage = evidence.coverage();
    assertThat(coverage.knownGaps())
        .contains(
            "job_instance not found in hot or archive tables",
            "payload_ref file_record not found in hot or archive tables",
            "no related file_record found in hot or archive tables",
            "no dispatch receipt found in hot or archive tables");
    assertThat(coverage.payloadFileResolved()).isFalse();
  }

  @Test
  @DisplayName("热表查不到时回退归档表, 覆盖范围标记为热表与归档")
  void shouldFallbackToArchiveTables_whenHotTablesMiss() {
    ResultVersionEntity version = version(10L, "FILE_RECORD", "file_record:11");
    when(resultVersionMapper.selectById("ta", 10L)).thenReturn(null);
    when(resultVersionMapper.selectArchivedById("ta", 10L)).thenReturn(version);
    when(lineageEvidenceMapper.selectJobInstance("ta", 101L)).thenReturn(null);
    when(lineageEvidenceMapper.selectArchivedJobInstance("ta", 101L))
        .thenReturn(JobInstance.builder().id(101L).jobCode("daily").build());
    when(lineageEvidenceMapper.selectPipelineInstances("ta", 101L)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectArchivedPipelineInstances("ta", 101L))
        .thenReturn(List.of(PipelineInstance.builder().id(21L).fileId(11L).build()));
    when(lineageEvidenceMapper.selectFileRecords("ta", 101L, 11L))
        .thenReturn(List.of(FileRecord.builder().id(11L).fileName("out.csv").build()));
    when(lineageEvidenceMapper.selectDispatchRecords("ta", 101L, List.of(11L)))
        .thenReturn(List.of());
    when(lineageEvidenceMapper.selectArchivedDispatchRecords("ta", 101L, List.of(11L)))
        .thenReturn(List.of(DispatchRecord.builder()
            .id(31L)
            .receiptStatus(FileReceiptStatus.SUCCESS.code())
            .build()));

    LineageEvidenceResponse evidence = service.evidenceForResultVersion("ta", 10L);

    LineageEvidenceResponse.LineageCoverage coverage = evidence.coverage();
    assertThat(coverage.scope()).isEqualTo("BFS_HOT_AND_ARCHIVE");
    LineageEvidenceResponse.LineageSources sources = coverage.sources();
    assertThat(sources)
        .isEqualTo(new LineageEvidenceResponse.LineageSources(
            "ARCHIVE", "ARCHIVE", "ARCHIVE", "HOT", "ARCHIVE"));
    assertThat(coverage.knownGaps()).isEmpty();
  }

  @Test
  @DisplayName("热表文件记录查不到时回退归档文件记录")
  void shouldFallbackToArchiveFileRecords_whenHotRecordsMiss() {
    ResultVersionEntity version = version(11L, "FILE_RECORD", "file_record:11");
    when(resultVersionMapper.selectById("ta", 11L)).thenReturn(null);
    when(resultVersionMapper.selectArchivedById("ta", 11L)).thenReturn(version);
    when(lineageEvidenceMapper.selectJobInstance("ta", 101L)).thenReturn(null);
    when(lineageEvidenceMapper.selectArchivedJobInstance("ta", 101L))
        .thenReturn(JobInstance.builder().id(101L).jobCode("daily").build());
    when(lineageEvidenceMapper.selectPipelineInstances("ta", 101L)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectArchivedPipelineInstances("ta", 101L))
        .thenReturn(List.of(PipelineInstance.builder().id(21L).fileId(11L).build()));
    when(lineageEvidenceMapper.selectFileRecords("ta", 101L, 11L)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectArchivedFileRecords("ta", 101L, 11L))
        .thenReturn(
            List.of(FileRecord.builder().id(11L).fileName("archived.csv").build()));
    when(lineageEvidenceMapper.selectDispatchRecords("ta", 101L, List.of(11L)))
        .thenReturn(List.of());
    when(lineageEvidenceMapper.selectArchivedDispatchRecords("ta", 101L, List.of(11L)))
        .thenReturn(List.of(DispatchRecord.builder()
            .id(31L)
            .receiptStatus(FileReceiptStatus.SUCCESS.code())
            .build()));

    LineageEvidenceResponse evidence = service.evidenceForResultVersion("ta", 11L);

    LineageEvidenceResponse.LineageCoverage coverage = evidence.coverage();
    assertThat(coverage.scope()).isEqualTo("BFS_HOT_AND_ARCHIVE");
    assertThat(coverage.payloadFileResolved()).isTrue();
    LineageEvidenceResponse.LineageSources sources = coverage.sources();
    assertThat(sources.fileRecords()).isEqualTo("ARCHIVE");
    assertThat(coverage.knownGaps()).isEmpty();
  }

  @Test
  @DisplayName("查询生效版本证据时委托生效版本查询服务解析")
  void shouldUseVersionQueryService_whenQueryingEffective() {
    ResultVersionEntity version = version(9L, "INLINE_JSON", null);
    when(resultVersionQueryService.findEffective("ta", "job:daily:2026-06-30"))
        .thenReturn(Optional.of(version));
    when(lineageEvidenceMapper.selectPipelineInstances("ta", 101L)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectFileRecords("ta", 101L, null)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectArchivedFileRecords("ta", 101L, null)).thenReturn(List.of());
    when(lineageEvidenceMapper.selectDispatchRecords("ta", 101L, List.of())).thenReturn(List.of());

    service.evidenceForEffective("ta", "job:daily:2026-06-30");

    verify(resultVersionQueryService).findEffective("ta", "job:daily:2026-06-30");
  }

  @Test
  @DisplayName("结果版本不存在时抛出未找到异常")
  void shouldRaiseNotFound_whenResultVersionMissing() {
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
