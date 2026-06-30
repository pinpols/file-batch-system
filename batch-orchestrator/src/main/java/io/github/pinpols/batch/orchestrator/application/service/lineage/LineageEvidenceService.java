package io.github.pinpols.batch.orchestrator.application.service.lineage;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.LineageEvidenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** BFS 管辖范围内的最小 lineage 证据链查询,不承担外部数据目录职责。 */
@Service
@RequiredArgsConstructor
public class LineageEvidenceService {

  private static final String FILE_RECORD_REF_PREFIX = "file_record:";

  private final ResultVersionMapper resultVersionMapper;
  private final ResultVersionQueryService resultVersionQueryService;
  private final LineageEvidenceMapper lineageEvidenceMapper;

  public Map<String, Object> evidenceForResultVersion(String tenantId, Long resultVersionId) {
    ResultVersionEntity version = resultVersionMapper.selectById(tenantId, resultVersionId);
    if (version == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.result_version.not_found");
    }
    return buildEvidence(version);
  }

  public Map<String, Object> evidenceForEffective(String tenantId, String businessKey) {
    ResultVersionEntity version =
        resultVersionQueryService
            .findEffective(tenantId, businessKey)
            .orElseThrow(
                () -> BizException.of(ResultCode.NOT_FOUND, "error.result_version.not_found"));
    return buildEvidence(version);
  }

  private Map<String, Object> buildEvidence(ResultVersionEntity version) {
    Long payloadFileId = payloadFileId(version);
    Map<String, Object> jobInstance =
        lineageEvidenceMapper.selectJobInstance(version.tenantId(), version.jobInstanceId());
    List<Map<String, Object>> pipelineInstances =
        lineageEvidenceMapper.selectPipelineInstances(version.tenantId(), version.jobInstanceId());
    List<Map<String, Object>> fileRecords =
        lineageEvidenceMapper.selectFileRecords(
            version.tenantId(), version.jobInstanceId(), payloadFileId);
    List<Map<String, Object>> files = nullToEmpty(fileRecords);
    List<Long> fileIds =
        files.stream().map(row -> longValue(row.get("id"))).filter(Objects::nonNull).toList();
    List<Map<String, Object>> dispatchRecords =
        lineageEvidenceMapper.selectDispatchRecords(
            version.tenantId(), version.jobInstanceId(), fileIds);

    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("resultVersion", resultVersion(version));
    evidence.put("jobInstance", emptyToNull(jobInstance));
    evidence.put("pipelineInstances", nullToEmpty(pipelineInstances));
    evidence.put("fileRecords", nullToEmpty(fileRecords));
    evidence.put("dispatchRecords", nullToEmpty(dispatchRecords));
    evidence.put(
        "coverage",
        coverage(
            version, payloadFileId, jobInstance, pipelineInstances, fileRecords, dispatchRecords));
    return evidence;
  }

  private static Map<String, Object> resultVersion(ResultVersionEntity v) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", v.id());
    row.put("tenantId", v.tenantId());
    row.put("businessKey", v.businessKey());
    row.put("versionNo", v.versionNo());
    row.put("jobInstanceId", v.jobInstanceId());
    row.put("status", v.status());
    row.put("effectiveAt", v.effectiveAt());
    row.put("deactivatedAt", v.deactivatedAt());
    row.put("payloadStorage", v.payloadStorage());
    row.put("payloadRef", v.payloadRef());
    row.put("generatedAt", v.generatedAt());
    row.put("generatedBy", v.generatedBy());
    row.put("promotionPolicy", v.promotionPolicy());
    row.put("dqGateStatus", v.dqGateStatus());
    return row;
  }

  private static Map<String, Object> coverage(
      ResultVersionEntity version,
      Long payloadFileId,
      Map<String, Object> jobInstance,
      List<Map<String, Object>> pipelineInstances,
      List<Map<String, Object>> fileRecords,
      List<Map<String, Object>> dispatchRecords) {
    List<Map<String, Object>> pipelines = nullToEmpty(pipelineInstances);
    List<Map<String, Object>> files = nullToEmpty(fileRecords);
    List<Map<String, Object>> dispatches = nullToEmpty(dispatchRecords);
    List<String> knownGaps = new ArrayList<>();
    if (jobInstance == null || jobInstance.isEmpty()) {
      knownGaps.add("job_instance not found or archived outside hot tables");
    }
    if (payloadFileId != null
        && files.stream().noneMatch(row -> payloadFileId.equals(longValue(row.get("id"))))) {
      knownGaps.add("payload_ref file_record not found");
    }
    if (files.isEmpty()) {
      knownGaps.add("no related file_record found in hot tables");
    }
    if (dispatches.isEmpty()) {
      knownGaps.add("no dispatch receipt found in hot tables");
    }

    Map<String, Object> coverage = new LinkedHashMap<>();
    coverage.put("scope", "BFS_HOT_TABLES");
    coverage.put("resultVersionId", version.id());
    coverage.put("jobInstanceFound", jobInstance != null && !jobInstance.isEmpty());
    coverage.put("payloadFileId", payloadFileId);
    coverage.put(
        "payloadFileResolved",
        payloadFileId == null
            || knownGaps.stream().noneMatch("payload_ref file_record not found"::equals));
    coverage.put("pipelineInstanceCount", pipelines.size());
    coverage.put("fileRecordCount", files.size());
    coverage.put("dispatchRecordCount", dispatches.size());
    coverage.put("knownGaps", knownGaps);
    return coverage;
  }

  private static Long payloadFileId(ResultVersionEntity version) {
    if (version == null
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

  private static Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return null;
    }
    try {
      return Long.valueOf(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static Map<String, Object> emptyToNull(Map<String, Object> row) {
    return row == null || row.isEmpty() ? null : row;
  }

  private static List<Map<String, Object>> nullToEmpty(List<Map<String, Object>> rows) {
    return rows == null ? List.of() : rows;
  }
}
