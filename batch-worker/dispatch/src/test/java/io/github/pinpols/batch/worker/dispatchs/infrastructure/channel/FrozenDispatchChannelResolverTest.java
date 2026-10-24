package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("分发 Worker 只消费审批快照中的渠道配置")
class FrozenDispatchChannelResolverTest {

  private static final String SNAPSHOT_JSON = """
      {"schemaVersion":1,"tenantId":"t1","channelCode":"CH1","channelId":3,
       "configuration":{"id":3,"tenant_id":"t1","channel_code":"CH1",
       "channel_type":"LOCAL","target_endpoint":"/tmp/out","auth_type":"NONE",
       "config_json":{},"receipt_policy":"NONE","timeout_seconds":0,
       "enabled":true,"is_deleted":false}}
      """;

  @Mock private SecretPayloadProtector protector;

  private FrozenDispatchChannelResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new FrozenDispatchChannelResolver(protector);
  }

  @Test
  @DisplayName("整 Workflow 重放按租户和通道身份解封冻结配置")
  void resolvesApprovedChannelSnapshot() {
    when(protector.revealEncrypted("ciphertext")).thenReturn(SNAPSHOT_JSON);
    String payload = """
        {"operationType":"WORKFLOW_RUN","channelCode":"CH1",
         "_frozenDispatchChannelsProtected":{"CH1":"ciphertext"}}
        """;

    Optional<Map<String, Object>> configuration = resolver.resolve("t1", "CH1", payload);

    assertThat(configuration).isPresent();
    Map<String, Object> resolved = configuration.orElseThrow();
    assertThat(resolved).containsEntry("channel_type", "LOCAL");
    assertThat(resolved).containsEntry("target_endpoint", "/tmp/out");
  }

  @Test
  @DisplayName("整 Workflow 重放缺少冻结通道配置时拒绝且不返回 latest 候选")
  void rejectsReplayWithoutApprovedChannelSnapshot() {
    String payload = """
        {"operationType":"WORKFLOW_RUN","channelCode":"CH1"}
        """;

    assertThatThrownBy(() -> resolver.resolve("t1", "CH1", payload))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("审批快照租户或通道身份与当前任务不一致时拒绝")
  void rejectsSnapshotIdentityMismatch() {
    when(protector.revealEncrypted("ciphertext")).thenReturn(SNAPSHOT_JSON);
    String payload = """
        {"operationType":"WORKFLOW_RUN","channelCode":"CH1",
         "_frozenDispatchChannelsProtected":{"CH1":"ciphertext"}}
        """;

    assertThatThrownBy(() -> resolver.resolve("other-tenant", "CH1", payload))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("重复根级操作类型或快照字段不按最后一个值静默覆盖")
  void rejectsDuplicateSecurityFields() {
    String payload = """
        {"operationType":"WORKFLOW_RUN","operationType":"MANUAL",
         "channelCode":"CH1","_frozenDispatchChannelsProtected":{"CH1":"ciphertext"}}
        """;

    assertThatThrownBy(() -> resolver.resolve("t1", "CH1", payload))
        .isInstanceOf(BizException.class);
  }
}
