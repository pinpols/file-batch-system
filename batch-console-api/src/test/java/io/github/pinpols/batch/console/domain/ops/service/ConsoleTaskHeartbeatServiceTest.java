package io.github.pinpols.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.ops.dto.TaskHeartbeatDetailsResponse;
import io.github.pinpols.batch.console.domain.ops.entity.JobTaskHeartbeatEntity;
import io.github.pinpols.batch.console.domain.ops.mapper.JobTaskMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("任务心跳详情读取:JSON 明细解析, 空值与非法内容降级, 缺失或跨租户抛业务异常")
class ConsoleTaskHeartbeatServiceTest {

  @Mock
  private JobTaskMapper jobTaskMapper;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private ConsoleTaskHeartbeatService service() {
    return new ConsoleTaskHeartbeatService(jobTaskMapper, objectMapper);
  }

  private JobTaskHeartbeatEntity entity(String details, Boolean cancel) {
    JobTaskHeartbeatEntity e = new JobTaskHeartbeatEntity();
    e.setId(42L);
    e.setTenantId("tx");
    e.setTaskStatus("RUNNING");
    e.setHeartbeatDetails(details);
    e.setHeartbeatAt(Instant.parse("2026-06-01T00:00:00Z"));
    e.setCancelRequested(cancel);
    return e;
  }

  @Test
  @DisplayName("明细映射:心跳明细解析为结构化节点, 任务标识与状态原样回填")
  void shouldParseJsonDetails_whenReadingHeartbeat() {
    when(jobTaskMapper.selectHeartbeatByTenantAndId("tx", 42L))
        .thenReturn(entity("{\"processed\":1200,\"total\":5000}", false));

    TaskHeartbeatDetailsResponse resp = service().getHeartbeatDetails("tx", 42L);

    assertThat(resp.taskId()).isEqualTo(42L);
    assertThat(resp.taskStatus()).isEqualTo("RUNNING");
    assertThat(resp.cancelRequested()).isFalse();
    assertThat(resp.details()).isInstanceOf(JsonNode.class);
    assertThat(((JsonNode) resp.details()).get("processed").asInt()).isEqualTo(1200);
  }

  @Test
  @DisplayName("空明细:明细为空时返回空值, 取消标记按未请求处理")
  void shouldReturnNullDetails_whenHeartbeatDetailsNull() {
    when(jobTaskMapper.selectHeartbeatByTenantAndId("tx", 42L)).thenReturn(entity(null, null));

    TaskHeartbeatDetailsResponse resp = service().getHeartbeatDetails("tx", 42L);

    assertThat(resp.details()).isNull();
    assertThat(resp.cancelRequested()).as("cancelRequested=null → false").isFalse();
  }

  @Test
  @DisplayName("非法明细:内容无法解析时降级为空值, 不抛异常")
  void shouldDegradeToNull_whenHeartbeatDetailsMalformed() {
    when(jobTaskMapper.selectHeartbeatByTenantAndId("tx", 42L))
        .thenReturn(entity("{not-json", false));

    assertThat(service().getHeartbeatDetails("tx", 42L).details()).isNull();
  }

  @Test
  @DisplayName("记录缺失:未命中或跨租户查询时抛出业务异常")
  void shouldThrowBizException_whenHeartbeatMissingOrCrossTenant() {
    when(jobTaskMapper.selectHeartbeatByTenantAndId("tx", 99L)).thenReturn(null);

    assertThatThrownBy(() -> service().getHeartbeatDetails("tx", 99L))
        .isInstanceOf(BizException.class);
  }
}
