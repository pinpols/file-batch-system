package com.example.batch.worker.processes.infrastructure.verifier;

import com.example.batch.common.enums.JobType;
import com.example.batch.common.verifier.ContentVerifier;
import com.example.batch.common.verifier.VerifyContext;
import com.example.batch.common.verifier.VerifyResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ADR-030 示例 verifier：PROCESS 任务必须 publish 出至少一条记录。
 *
 * <p>判定逻辑：PROCESS worker 完成 5-stage WAP 后，{@code publishedCount} 为 0 而非空（即 worker 明确"我没发布任何东西"）→ 记
 * {@code PROCESS_PUBLISHED_ZERO} 失败。
 *
 * <p>豁免：当 {@code publishedCount} 为 null（worker 未上报这个字段），视为非 publish 阶段，pass。
 */
@Component
public class ProcessPublishedCountVerifier implements ContentVerifier {

  @Override
  public String code() {
    return "PROCESS_PUBLISHED_COUNT";
  }

  @Override
  public Set<JobType> appliesTo() {
    return Set.of(JobType.PROCESS);
  }

  @Override
  public VerifyResult verify(VerifyContext context) {
    Object raw = context.property("publishedCount");
    if (raw == null) {
      // worker 未上报；不属于本 verifier 的判定范围
      return VerifyResult.pass();
    }
    long count = toLong(raw);
    if (count > 0) {
      return VerifyResult.pass();
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("publishedCount", count);
    evidence.put("processedCount", context.property("processedCount"));
    evidence.put("batchKey", context.property("batchKey"));
    return VerifyResult.fail(
        "PROCESS_PUBLISHED_ZERO", "PROCESS task reported success but publishedCount=0", evidence);
  }

  private static long toLong(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(value.toString().trim());
    } catch (NumberFormatException ignored) {
      return -1L;
    }
  }
}
