package io.github.pinpols.batch.console.domain.ops.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.ops.dto.TaskHeartbeatDetailsResponse;
import io.github.pinpols.batch.console.domain.ops.entity.JobTaskHeartbeatEntity;
import io.github.pinpols.batch.console.domain.ops.mapper.JobTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * FE 2-C:读 {@code batch.job_task} 最新心跳进度,把 JSONB details 解析成 JSON 透传 FE。
 *
 * <p>租户作用域已在 mapper WHERE 强制(跨租户读不到 → null → NOT_FOUND);本服务只读,不回写状态(状态主机是 orchestrator)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleTaskHeartbeatService {

  private final JobTaskMapper jobTaskMapper;
  private final ObjectMapper objectMapper;

  /** 读指定租户下某 task 的最新心跳进度;不存在(或不属于该租户)抛 NOT_FOUND。 */
  public TaskHeartbeatDetailsResponse getHeartbeatDetails(String tenantId, Long taskId) {
    JobTaskHeartbeatEntity entity = jobTaskMapper.selectHeartbeatByTenantAndId(tenantId, taskId);
    if (entity == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.common.not_found_detail", taskId);
    }
    return new TaskHeartbeatDetailsResponse(
        entity.getId(),
        entity.getTaskStatus(),
        parseDetails(entity.getHeartbeatDetails(), taskId),
        entity.getHeartbeatAt(),
        Boolean.TRUE.equals(entity.getCancelRequested()));
  }

  /** JSONB 文本解析成 JSON 结构;空 = null;解析异常降级为 null(不让坏数据 500),并 WARN 带 taskId。 */
  private Object parseDetails(String raw, Long taskId) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readTree(raw);
    } catch (JsonProcessingException e) {
      log.warn("job_task heartbeat_details 解析失败(taskId={}): {}", taskId, e.getMessage());
      return null;
    }
  }
}
