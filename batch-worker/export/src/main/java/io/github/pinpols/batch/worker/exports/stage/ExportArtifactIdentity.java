package io.github.pinpols.batch.worker.exports.stage;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import java.util.Map;

/** 导出产物归属于一个 Job 实例；同实例重领复用，新实例不能借用旧运行的对象。 */
final class ExportArtifactIdentity {

  private ExportArtifactIdentity() {}

  static Long jobInstanceId(ExportJobContext context) {
    return positiveLong(context.getAttributes().get(PipelineRuntimeKeys.JOB_INSTANCE_ID));
  }

  static boolean belongsToJob(ExportJobContext context, Map<String, Object> record) {
    Long currentJob = jobInstanceId(context);
    Map<?, ?> metadata = metadata(record);
    Long owner = positiveLong(metadata.get(PipelineRuntimeKeys.JOB_INSTANCE_ID));
    return EmptyChecks.isNotNull(currentJob) && currentJob.equals(owner);
  }

  static Map<?, ?> metadata(Map<String, Object> record) {
    Object value = record.get("metadata_json");
    if (value instanceof Map<?, ?> map) {
      return map;
    }
    if (EmptyChecks.isNotNull(value)) {
      try {
        Object parsed = JsonUtils.fromJson(value.toString(), Object.class);
        if (parsed instanceof Map<?, ?> map) {
          return map;
        }
      } catch (IllegalArgumentException invalidMetadata) {
        // 元数据不可验证时不接受产物归属，调用方按冲突关闭复用。
      }
    }
    return Map.of();
  }

  private static Long positiveLong(Object value) {
    if (EmptyChecks.isNull(value)) return null;
    try {
      long number = Long.parseLong(value.toString());
      return number > 0 ? number : null;
    } catch (NumberFormatException invalidIdentity) {
      return null;
    }
  }
}
