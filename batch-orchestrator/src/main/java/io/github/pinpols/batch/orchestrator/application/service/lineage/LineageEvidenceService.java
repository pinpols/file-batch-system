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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** BFS 管辖范围内的最小 lineage 证据链查询，不承担外部数据目录职责。 */
@Service
@RequiredArgsConstructor
public class LineageEvidenceService {

  private static final String FILE_RECORD_REF_PREFIX = "file_record:";
  private static final String HOT = "HOT";
  private static final String ARCHIVE = "ARCHIVE";
  private static final String HOT_AND_ARCHIVE = "HOT_AND_ARCHIVE";
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
    List<FileRecord> hotFiles = nullToEmpty(lineageEvidenceMapper.selectFileRecords(
        version.tenantId(), version.jobInstanceId(), payloadFileId));
    List<FileRecord> archivedFiles = nullToEmpty(lineageEvidenceMapper.selectArchivedFileRecords(
        version.tenantId(), version.jobInstanceId(), payloadFileId));
    TreeMap<Long, FileRecord> filesById = new TreeMap<>();
    boolean archivedJob = EmptyChecks.isNotNull(jobInstance) && ARCHIVE.equals(jobInstanceSource);
    List<FileRecord> preferredFiles = archivedJob ? archivedFiles : hotFiles;
    List<FileRecord> otherFiles = archivedJob ? hotFiles : archivedFiles;
    preferredFiles.forEach(file -> filesById.put(file.id(), file));
    int preferredCount = filesById.size();
    otherFiles.forEach(file -> filesById.putIfAbsent(file.id(), file));
    boolean otherFilesUsed = filesById.size() > preferredCount;
    String preferredSource = archivedJob ? ARCHIVE : HOT;
    String otherSource = archivedJob ? HOT : ARCHIVE;
    String fileSource =
        preferredCount == 0 ? otherSource : otherFilesUsed ? HOT_AND_ARCHIVE : preferredSource;
    List<FileRecord> files = List.copyOf(filesById.values());
    List<Long> fileIds =
        files.stream().map(FileRecord::id).filter(Objects::nonNull).toList();
    List<DispatchRecord> hotDispatches = nullToEmpty(lineageEvidenceMapper.selectDispatchRecords(
        version.tenantId(), version.jobInstanceId(), fileIds));
    List<DispatchRecord> archivedDispatches =
        nullToEmpty(lineageEvidenceMapper.selectArchivedDispatchRecords(
            version.tenantId(), version.jobInstanceId(), fileIds));
    TreeMap<Long, DispatchRecord> dispatchesById = new TreeMap<>(Comparator.reverseOrder());
    hotDispatches.forEach(dispatch -> dispatchesById.put(dispatch.id(), dispatch));
    archivedDispatches.forEach(dispatch -> dispatchesById.putIfAbsent(dispatch.id(), dispatch));
    List<DispatchRecord> dispatches =
        dispatchesById.values().stream().limit(100).toList();
    Set<Long> hotDispatchIds =
        hotDispatches.stream().map(DispatchRecord::id).collect(Collectors.toSet());
    boolean hotDispatchUsed =
        dispatches.stream().anyMatch(dispatch -> hotDispatchIds.contains(dispatch.id()));
    boolean archiveDispatchUsed =
        dispatches.stream().anyMatch(dispatch -> !hotDispatchIds.contains(dispatch.id()));
    String dispatchSource = hotDispatchUsed ? archiveDispatchUsed ? HOT_AND_ARCHIVE : HOT : ARCHIVE;
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
    ResultVersion versionEvidence = resultVersion(version);
    LineageCoverage lineageCoverage = coverage(coverageInput);
    return new LineageEvidenceResponse(
        versionEvidence, jobInstance, pipelines, files, dispatches, lineageCoverage);
  }

  private static ResultVersion resultVersion(ResultVersionEntity v) {
    return ResultVersion.builder()
        .id(v.id())
        .tenantId(v.tenantId())
        .businessKey(v.businessKey())
        .versionNo(v.versionNo())
        .jobInstanceId(v.jobInstanceId())
        .status(v.status())
        .effectiveAt(v.effectiveAt())
        .deactivatedAt(v.deactivatedAt())
        .payloadStorage(v.payloadStorage())
        .payloadRef(v.payloadRef())
        .generatedAt(v.generatedAt())
        .generatedBy(v.generatedBy())
        .promotionPolicy(v.promotionPolicy())
        .dqGateStatus(v.dqGateStatus())
        .build();
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
        || (!EmptyChecks.isEmpty(input.fileRecords())
            && (ARCHIVE.equals(input.fileSource()) || HOT_AND_ARCHIVE.equals(input.fileSource())))
        || (!EmptyChecks.isEmpty(input.dispatchRecords())
            && (ARCHIVE.equals(input.dispatchSource())
                || HOT_AND_ARCHIVE.equals(input.dispatchSource())));
    String jobSource = jobFound ? input.jobInstanceSource() : NONE;
    String pipelineSource =
        EmptyChecks.isEmpty(input.pipelineInstances()) ? NONE : input.pipelineSource();
    String fileSource = EmptyChecks.isEmpty(input.fileRecords()) ? NONE : input.fileSource();
    String dispatchSource =
        EmptyChecks.isEmpty(input.dispatchRecords()) ? NONE : input.dispatchSource();
    LineageSources sources = new LineageSources(
        input.resultVersionSource(), jobSource, pipelineSource, fileSource, dispatchSource);
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
    boolean resultVersionHasNoFileRecordReference = EmptyChecks.isNull(version)
        || !"FILE_RECORD".equals(version.payloadStorage())
        || !Texts.hasText(version.payloadRef())
        || !version.payloadRef().startsWith(FILE_RECORD_REF_PREFIX);
    if (resultVersionHasNoFileRecordReference) {
      return null;
    }
    String payloadReferenceSuffix = version.payloadRef().substring(FILE_RECORD_REF_PREFIX.length());
    String id = payloadReferenceSuffix.trim();
    try {
      return Long.valueOf(id);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static <T> List<T> nullToEmpty(List<T> rows) {
    return EmptyChecks.isNull(rows) ? List.of() : rows;
  }

  @Builder
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
