package io.github.pinpols.batch.console.domain.ops.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.common.utils.SensitiveEvidenceMasking;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.ops.dto.TaskExecutionAuditResponse;
import io.github.pinpols.batch.console.domain.ops.mapper.JobTaskMapper;
import io.github.pinpols.batch.console.domain.ops.view.JobTaskExecutionAuditView;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 任务执行审计（BIZ-20261113-02）：读 {@code batch.job_task} 派单期生效参数与执行结果，脱敏后返回。
 *
 * <p>原子任务的 shell 命令、SQL 文本等在派单时已随 {@code effective_parameters} 持久化并随任务归档，本服务只补查询入口，
 * 不另建审计表。脱敏规则：
 *
 * <ul>
 *   <li>{@code env}、{@code headers} 下的所有值替换为 {@value SecretMasking#MASKED_VALUE}，只保留键名；
 *   <li>{@code auth} 整体替换；
 *   <li>其余字段按 {@link SensitiveEvidenceMasking} 递归遮蔽敏感键、端点和已识别的凭据表达式。
 * </ul>
 *
 * <p>租户作用域在 mapper WHERE 强制，跨租户读不到即 NOT_FOUND；只读，不回写状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleTaskExecutionAuditService {

  private static final Set<String> VALUE_MASKED_MAPS = Set.of("env", "headers");
  private static final Set<String> FULLY_MASKED_KEYS = Set.of("auth");
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final JobTaskMapper jobTaskMapper;
  private final ObjectMapper objectMapper;
  private final TenantIdResolver tenantGuard;

  /** 读指定租户下某任务的执行审计；不存在或不属于该租户时抛 NOT_FOUND。 */
  public TaskExecutionAuditResponse getExecutionAudit(String tenantId, Long taskId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    JobTaskExecutionAuditView task =
        jobTaskMapper.selectExecutionAuditByTenantAndId(resolvedTenantId, taskId);
    if (EmptyChecks.isNull(task)) {
      throw BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey(), taskId);
    }
    Map<String, Object> parameters = maskParameters(parse(task.getEffectiveParameters(), taskId));
    Map<String, Object> resultSummary =
        SensitiveEvidenceMasking.maskMetadata(parse(task.getResultSummary(), taskId));
    String errorMessage = EmptyChecks.isNull(task.getErrorMessage())
        ? null
        : SensitiveEvidenceMasking.maskText(task.getErrorMessage());
    TaskExecutionAuditResponse.Outcome outcome = new TaskExecutionAuditResponse.Outcome(
        task.getAssignedWorkerCode(),
        task.getStartedAt(),
        task.getFinishedAt(),
        resultSummary,
        task.getErrorCode(),
        errorMessage);
    return new TaskExecutionAuditResponse(
        task.getId(),
        task.getJobInstanceId(),
        task.getJobCode(),
        task.getTaskStatus(),
        parameters,
        outcome);
  }

  static Map<String, Object> maskParameters(Map<String, Object> parameters) {
    if (EmptyChecks.isNull(parameters)) {
      return null;
    }
    Map<String, Object> masked = new LinkedHashMap<>();
    parameters.forEach((key, value) -> {
      String normalized = EmptyChecks.isNull(key) ? "" : key.toLowerCase(Locale.ROOT);
      if (FULLY_MASKED_KEYS.contains(normalized)) {
        masked.put(key, SecretMasking.MASKED_VALUE);
      } else if (VALUE_MASKED_MAPS.contains(normalized) && value instanceof Map<?, ?> map) {
        Map<String, Object> keysOnly = new LinkedHashMap<>();
        map.keySet()
            .forEach(name -> keysOnly.put(String.valueOf(name), SecretMasking.MASKED_VALUE));
        masked.put(key, keysOnly);
      } else {
        masked.put(key, maskValue(key, value));
      }
    });
    return masked;
  }

  private static Object maskValue(String key, Object value) {
    return SecretMasking.isSensitive(key)
        ? SecretMasking.MASKED_VALUE
        : SensitiveEvidenceMasking.maskJsonValue(value);
  }

  /** JSONB 文本解析为对象；空或解析失败返回 null，避免坏数据导致 500。 */
  private Map<String, Object> parse(String raw, Long taskId) {
    if (!Texts.hasText(raw)) {
      return null;
    }
    try {
      return objectMapper.readValue(raw, MAP_TYPE);
    } catch (JsonProcessingException e) {
      log.warn(
          "Failed to parse job_task audit JSON (taskId={}): {}",
          taskId,
          SwallowedExceptionLogger.summary(e));
      return null;
    }
  }
}
