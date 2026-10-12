package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.worker.dispatchs.mapper.DispatchSecretVersionMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("分发密钥解析:固定版本、紧急撤销和旧渠道兼容")
class DispatchSecretResolverTest {

  @Mock
  private DispatchSecretVersionMapper secretVersionMapper;

  @Mock
  private SecretPayloadProtector secretPayloadProtector;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("按实例快照中的版本 ID 读取密钥,不改写版本引用")
  void resolvesPinnedVersion_whenVersionMatchesReference() {
    DispatchSecretResolver resolver = resolver();
    Map<String, Object> payload = payload("dispatch/key", 42L);
    Map<String, Object> channelConfig =
        new LinkedHashMap<>(Map.of("channel_type", "SFTP", "secret_ref", "dispatch/key"));
    when(secretVersionMapper.selectPayloadForDispatch("t1", 42L)).thenReturn("protected");
    when(secretPayloadProtector.reveal("protected"))
        .thenReturn("{\"sftp_password\":\"runtime-secret\"}");

    var resolvedKeys = resolver.resolveInto("t1", "CH1", payload, channelConfig);

    assertThat(channelConfig).containsEntry("sftp_password", "runtime-secret");
    assertThat(resolvedKeys).containsExactly("sftp_password");
    assertThat(payload).doesNotContainValue("runtime-secret");
    verify(secretVersionMapper).selectPayloadForDispatch("t1", 42L);
  }

  @Test
  @DisplayName("撤销或过期版本查询不到时拒绝外发")
  void rejectsVersion_whenRevokedOrExpired() {
    DispatchSecretResolver resolver = resolver();
    Map<String, Object> payload = payload("dispatch/key", 42L);
    Map<String, Object> channelConfig = new LinkedHashMap<>(Map.of("secret_ref", "dispatch/key"));
    when(secretVersionMapper.selectPayloadForDispatch("t1", 42L)).thenReturn(null);

    assertThatThrownBy(() -> resolver.resolveInto("t1", "CH1", payload, channelConfig))
        .isInstanceOf(DispatchSecretBlockedException.class)
        .hasMessage("DISPATCH_SECRET_REVOKED_OR_EXPIRED");
    verifyNoInteractions(secretPayloadProtector);
  }

  @Test
  @DisplayName("实例固定引用与渠道快照不一致时拒绝读取其他密钥")
  void rejectsSecret_whenReferenceMismatches() {
    DispatchSecretResolver resolver = resolver();
    Map<String, Object> payload = payload("dispatch/old-key", 42L);
    Map<String, Object> channelConfig =
        new LinkedHashMap<>(Map.of("secret_ref", "dispatch/new-key"));

    assertThatThrownBy(() -> resolver.resolveInto("t1", "CH1", payload, channelConfig))
        .isInstanceOf(DispatchSecretBlockedException.class)
        .hasMessage("DISPATCH_SECRET_VERSION_INVALID");
    verifyNoInteractions(secretVersionMapper, secretPayloadProtector);
  }

  @Test
  @DisplayName("渠道声明了密钥引用但任务没有固定版本时拒绝静默回退")
  void rejectsSecret_whenBindingIsMissing() {
    DispatchSecretResolver resolver = resolver();
    Map<String, Object> channelConfig = new LinkedHashMap<>(Map.of("secret_ref", "dispatch/key"));

    assertThatThrownBy(() -> resolver.resolveInto("t1", "CH1", Map.of(), channelConfig))
        .isInstanceOf(DispatchSecretBlockedException.class)
        .hasMessage("DISPATCH_SECRET_BINDING_MISSING");
    verifyNoInteractions(secretVersionMapper, secretPayloadProtector);
  }

  @Test
  @DisplayName("未配置 SecretVersion 的历史渠道保持原运行行为")
  void acceptsLegacyChannel_whenSecretReferenceIsAbsent() {
    resolver().resolveInto("t1", "CH1", Map.of(), new LinkedHashMap<>());

    verifyNoInteractions(secretVersionMapper, secretPayloadProtector);
  }

  private DispatchSecretResolver resolver() {
    return new DispatchSecretResolver(secretVersionMapper, secretPayloadProtector, objectMapper);
  }

  private Map<String, Object> payload(String secretRef, long versionId) {
    return Map.of(
        DispatchRuntimeKeys.DISPATCH_SECRET_BINDINGS,
        Map.of(
            "CH1", Map.of("secretRef", secretRef, "secretVersionId", versionId, "versionNo", 3)));
  }
}
