package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.OrchestratorClientProperties;
import io.github.pinpols.batch.common.dto.WorkerHeartbeatDto;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * register / heartbeat 请求体映射：worker 侧把运行身份与真实监听端口装进 {@link WorkerHeartbeatDto}。
 *
 * <p>本测试盯 V221 新增 {@code port} 的「解析 → 上报」最后一跳：{@code AbstractWorkerLoop} 解析出的端口必须真的进
 * DTO，否则 orchestrator 与 console 永远拿不到值——端口解析侧的单测覆盖不到这一跳。
 */
@DisplayName("Worker 注册客户端: 心跳请求体的身份与端口映射")
class HttpWorkerRegistryClientTest {

  /**
   * 只有 {@link PipelineStageProgressRegistry} 参与 {@code toHeartbeatDto}；RestClient 与 Environment 在该方法内不被触碰，
   * 传 null 只为占位（若将来被触碰会立即 NPE 而非静默返回 null）。
   */
  private final HttpWorkerRegistryClient client = new HttpWorkerRegistryClient(
      new OrchestratorClientProperties(),
      new BatchSecurityProperties(),
      null,
      null,
      new PipelineStageProgressRegistry());

  @Test
  @DisplayName("映射心跳请求体时携带 Worker 真实监听端口")
  void shouldCarryWorkerPort_whenMappingHeartbeatDto() {
    WorkerRegistration registration = registration();
    registration.setPort(18083);

    assertThat(client.toHeartbeatDto(registration).port()).isEqualTo(18083);
  }

  @Test
  @DisplayName("映射心跳请求体时, 实例标识, 实例池编码, 分组与主机名逐项对应")
  void shouldMapInstanceIdentityAndPoolCode_whenMappingHeartbeatDto() {
    WorkerRegistration registration = registration();
    registration.setWorkerId("import-node-1-pod-a");
    registration.setWorkerCode("import-node-1");
    registration.setWorkerGroup("IMPORT");
    registration.setHost("worker-host");
    registration.setPort(18083);

    WorkerHeartbeatDto dto = client.toHeartbeatDto(registration);

    assertThat(dto.workerCode()).isEqualTo("import-node-1-pod-a");
    assertThat(dto.workerPoolCode()).isEqualTo("import-node-1");
    assertThat(dto.workerGroup()).isEqualTo("IMPORT");
    assertThat(dto.hostName()).isEqualTo("worker-host");
    assertThat(dto.port()).isEqualTo(18083);
  }

  @Test
  @DisplayName("端口经序列化再反序列化后保持不变, 且序列化文本中包含该端口字段")
  void shouldPreservePort_whenSerializedAndParsed() throws Exception {
    // wire 契约：port 必须能序列化再反序列化回来（@Builder 不改变 record 的 wire 格式与 Jackson 绑定方式）。
    WorkerRegistration registration = registration();
    registration.setPort(18085);

    ObjectMapper mapper = JsonUtils.newDefaultMapper();
    String json = mapper.writeValueAsString(client.toHeartbeatDto(registration));
    WorkerHeartbeatDto parsed = mapper.readValue(json, WorkerHeartbeatDto.class);

    assertThat(json).contains("\"port\":18085");
    assertThat(parsed.port()).isEqualTo(18085);
  }

  private static WorkerRegistration registration() {
    WorkerRegistration registration = new WorkerRegistration();
    registration.setTenantId("default-tenant");
    registration.setStatus(WorkerRegistryStatus.ONLINE.code());
    // lastHeartbeatAt 故意留 null：覆盖 toHeartbeatDto 的 utcNow() 兜底分支。
    return registration;
  }
}
