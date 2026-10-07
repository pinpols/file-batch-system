package io.github.pinpols.batch.orchestrator.application.service.lineage;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.DispatchRecord;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.FileRecord;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.JobInstance;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.LineageCoverage;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.LineageSources;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.PipelineInstance;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.ResultVersion;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.LineageEvidenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** BFS 管辖范围内的最小 lineage 证据链查询，不承担外部数据目录职责。 */
@Service
@RequiredArgsConstructor
public class LineageEvidenceService {

  private static final String FILE_RECORD_REF_PREFIX = "file_record:";
  private static final String HOT = "HOT";
  private static final String ARCHIVE = "ARCHIVE";
  private static final String NONE = "NONE";

  private final ResultVersionMapper resultVersionMapper;
  private final ResultVersionQueryService resultVersionQueryService;
  private final LineageEvidenceMapper lineageEvidenceMapper;

  public LineageEvidenceResponse evidenceForResultVersion(String tenantId, Long resultVersionId) {
    ResultVersionEntity version = resultVersionMapper.selectById(tenantId, resultVersionId);
    String resultVersionSource = HOT;
    if (EmptyChecks.isNull(version)) {
      version = resultVersionMapper.selectArchivedById(tenantId, resultVersionId);
      resultVersionSource = ARCHIVE;
    }
    if (EmptyChecks.isNull(version)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.result_version.not_found");
    }
    return buildEvidence(version, resultVersionSource);
  }

  public LineageEvidenceResponse evidenceForEffective(String tenantId, String businessKey) {
    ResultVersionEntity version = resultVersionQueryService
        .findEffective(tenantId, businessKey)
        .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "error.result_version.not_found"));
    return buildEvidence(version, HOT);
  }

  private LineageEvidenceResponse buildEvidence(
      ResultVersionEntity version, String resultVersionSource) {
    Long payloadFileId = payloadFileId(version);
    JobInstance jobInstance =
        lineageEvidenceMapper.selectJobInstance(version.tenantId(), version.jobInstanceId());
    String jobInstanceSource = HOT;
    if (EmptyChecks.isNull(jobInstance)) {
      jobInstance = lineageEvidenceMapper.selectArchivedJobInstance(
          version.tenantId(), version.jobInstanceId());
      jobInstanceSource = ARCHIVE;
    }
    List<PipelineInstance> pipelines =
        lineageEvidenceMapper.selectPipelineInstances(version.tenantId(), version.jobInstanceId());
    String pipelineSource = HOT;
    if (EmptyChecks.isEmpty(pipelines)) {
      pipelines = lineageEvidenceMapper.selectArchivedPipelineInstances(
          version.tenantId(), version.jobInstanceId());
      pipelineSource = ARCHIVE;
    }
    List<FileRecord> files = lineageEvidenceMapper.selectFileRecords(
        version.tenantId(), version.jobInstanceId(), payloadFileId);
    String fileSource = HOT;
    if (EmptyChecks.isEmpty(files)) {
      files = lineageEvidenceMapper.selectArchivedFileRecords(
          version.tenantId(), version.jobInstanceId(), payloadFileId);
      fileSource = ARCHIVE;
    }
    files = nullToEmpty(files);
    List<Long> fileIds =
        files.stream().map(FileRecord::id).filter(Objects::nonNull).toList();
    List<DispatchRecord> dispatches = lineageEvidenceMapper.selectDispatchRecords(
        version.tenantId(), version.jobInstanceId(), fileIds);
    String dispatchSource = HOT;
    if (EmptyChecks.isEmpty(dispatches)) {
      dispatches = lineageEvidenceMapper.selectArchivedDispatchRecords(
          version.tenantId(), version.jobInstanceId(), fileIds);
      dispatchSource = ARCHIVE;
    }
    pipelines = nullToEmpty(pipelines);
    dispatches = nullToEmpty(dispatches);
    EvidenceCoverageInput coverageInput = new EvidenceCoverageInput(
        version,
        resultVersionSource,
        payloadFileId,
        jobInstance,
        jobInstanceSource,
        pipelines,
        pipelineSource,
        files,
        fileSource,
        dispatches,
        dispatchSource);
    return new LineageEvidenceResponse(
        resultVersion(version), jobInstance, pipelines, files, dispatches, coverage(coverageInput));
  }

  private static ResultVersion resultVersion(ResultVersionEntity v) {
    return new ResultVersion(
        v.id(),
        v.tenantId(),
        v.businessKey(),
        v.versionNo(),
        v.jobInstanceId(),
        v.status(),
        v.effectiveAt(),
        v.deactivatedAt(),
        v.payloadStorage(),
        v.payloadRef(),
        v.generatedAt(),
        v.generatedBy(),
        v.promotionPolicy(),
        v.dqGateStatus());
  }

  private static LineageCoverage coverage(EvidenceCoverageInput input) {
    List<String> knownGaps = new ArrayList<>();
    boolean jobFound = EmptyChecks.isNotNull(input.jobInstance());
    boolean payloadResolved = EmptyChecks.isNull(input.payloadFileId())
        || input.fileRecords().stream().anyMatch(row -> input.payloadFileId().equals(row.id()));
    if (!jobFound) {
      knownGaps.add("job_instance not found in hot or archive tables");
    }
    if (!payloadResolved) {
      knownGaps.add("payload_ref file_record not found in hot or archive tables");
    }
    if (EmptyChecks.isEmpty(input.fileRecords())) {
      knownGaps.add("no related file_record found in hot or archive tables");
    }
    if (EmptyChecks.isEmpty(input.dispatchRecords())) {
      knownGaps.add("no dispatch receipt found in hot or archive tables");
    }
    boolean archiveUsed = ARCHIVE.equals(input.resultVersionSource())
        || (jobFound && ARCHIVE.equals(input.jobInstanceSource()))
        || (!EmptyChecks.isEmpty(input.pipelineInstances())
            && ARCHIVE.equals(input.pipelineSource()))
        || (!EmptyChecks.isEmpty(input.fileRecords()) && ARCHIVE.equals(input.fileSource()))
        || (!EmptyChecks.isEmpty(input.dispatchRecords())
            && ARCHIVE.equals(input.dispatchSource()));
    LineageSources sources = new LineageSources(
        input.resultVersionSource(),
        jobFound ? input.jobInstanceSource() : NONE,
        EmptyChecks.isEmpty(input.pipelineInstances()) ? NONE : input.pipelineSource(),
        EmptyChecks.isEmpty(input.fileRecords()) ? NONE : input.fileSource(),
        EmptyChecks.isEmpty(input.dispatchRecords()) ? NONE : input.dispatchSource());
    return new LineageCoverage(
        archiveUsed ? "BFS_HOT_AND_ARCHIVE" : "BFS_HOT_TABLES",
        input.version().id(),
        sources,
        jobFound,
        input.payloadFileId(),
        payloadResolved,
        input.pipelineInstances().size(),
        input.fileRecords().size(),
        input.dispatchRecords().size(),
        knownGaps);
  }

  private static Long payloadFileId(ResultVersionEntity version) {
    if (EmptyChecks.isNull(version)
        || !"FILE_RECORD".equals(version.payloadStorage())
        || !Texts.hasText(version.payloadRef())
        || !version.payloadRef().startsWith(FILE_RECORD_REF_PREFIX)) {
      return null;
    }
    String id = version.payloadRef().substring(FILE_RECORD_REF_PREFIX.length()).trim();
    try {
      return Long.valueOf(id);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static <T> List<T> nullToEmpty(List<T> rows) {
    return EmptyChecks.isNull(rows) ? List.of() : rows;
  }

  private record EvidenceCoverageInput(
      ResultVersionEntity version,
      String resultVersionSource,
      Long payloadFileId,
      JobInstance jobInstance,
      String jobInstanceSource,
      List<PipelineInstance> pipelineInstances,
      String pipelineSource,
      List<FileRecord> fileRecords,
      String fileSource,
      List<DispatchRecord> dispatchRecords,
      String dispatchSource) {}
}
