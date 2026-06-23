package io.github.pinpols.batch.worker.exports.infrastructure.verifier;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.verifier.ContentVerifier;
import io.github.pinpols.batch.common.verifier.VerifyContext;
import io.github.pinpols.batch.common.verifier.VerifyResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ADR-030 示例 verifier：导出产物非空。
 *
 * <p>触发时机：EXPORT job 的 task 终态 SUCCESS 后调用。判定逻辑只看 payload 里 worker 已经填的 {@code recordCount} 与
 * {@code fileSizeBytes}：均为 0 时记 EXPORT_FILE_EMPTY 失败，避免"任务 status=SUCCESS 但导出空文件"被误当成正常完成。
 *
 * <p>不做：开桶读 MinIO 二次确认大小（保持 SPI 实现轻量；二次确认走 ExportContentVerificationE2eIT 在端到端层面覆盖）。
 */
@Component
public class ExportFileNonEmptyVerifier implements ContentVerifier {

  @Override
  public String code() {
    return "EXPORT_FILE_NON_EMPTY";
  }

  @Override
  public Set<JobType> appliesTo() {
    return Set.of(JobType.EXPORT);
  }

  @Override
  public VerifyResult verify(VerifyContext context) {
    long recordCount = longValue(context.property("recordCount"));
    long fileSize = longValue(context.property("fileSizeBytes"));
    if (recordCount > 0 || fileSize > 0) {
      return VerifyResult.pass();
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("recordCount", recordCount);
    evidence.put("fileSizeBytes", fileSize);
    evidence.put("fileId", context.property("fileId"));
    return VerifyResult.fail(
        "EXPORT_FILE_EMPTY", "Export task reported success but produced empty file", evidence);
  }

  private static long longValue(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    if (value instanceof String s) {
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException ignored) {
        // 继续尝试下一种解析方式
      }
    }
    return 0L;
  }
}
