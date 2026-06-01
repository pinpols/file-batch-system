package com.example.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.ops.dto.TaskHeartbeatDetailsResponse;
import com.example.batch.console.domain.ops.entity.JobTaskHeartbeatEntity;
import com.example.batch.console.domain.ops.mapper.JobTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleTaskHeartbeatServiceTest {

  @Mock private JobTaskMapper jobTaskMapper;

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
  void parsesJsonDetailsAndMapsFields() {
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
  void nullDetailsYieldsNull() {
    when(jobTaskMapper.selectHeartbeatByTenantAndId("tx", 42L)).thenReturn(entity(null, null));

    TaskHeartbeatDetailsResponse resp = service().getHeartbeatDetails("tx", 42L);

    assertThat(resp.details()).isNull();
    assertThat(resp.cancelRequested()).as("cancelRequested=null → false").isFalse();
  }

  @Test
  void malformedDetailsDegradeToNull() {
    when(jobTaskMapper.selectHeartbeatByTenantAndId("tx", 42L))
        .thenReturn(entity("{not-json", false));

    assertThat(service().getHeartbeatDetails("tx", 42L).details()).isNull();
  }

  @Test
  void throwsNotFoundWhenMissingOrCrossTenant() {
    when(jobTaskMapper.selectHeartbeatByTenantAndId("tx", 99L)).thenReturn(null);

    assertThatThrownBy(() -> service().getHeartbeatDetails("tx", 99L))
        .isInstanceOf(BizException.class);
  }
}
