package io.github.pinpols.batch.console.web.response.ops;

import java.util.List;
import java.util.Map;

/** BFS 边界内的结果版本证据链；关联表行保持半结构化以兼容归档表演进。 */
public record LineageEvidenceResponse(
    Map<String, Object> resultVersion,
    Map<String, Object> jobInstance,
    List<Map<String, Object>> pipelineInstances,
    List<Map<String, Object>> fileRecords,
    List<Map<String, Object>> dispatchRecords,
    LineageCoverage coverage) {

  public record LineageCoverage(
      String scope,
      Long resultVersionId,
      Map<String, String> sources,
      boolean jobInstanceFound,
      Long payloadFileId,
      boolean payloadFileResolved,
      int pipelineInstanceCount,
      int fileRecordCount,
      int dispatchRecordCount,
      List<String> knownGaps) {}
}
