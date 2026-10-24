package io.github.pinpols.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowReplayCapabilityService.Status;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
@DisplayName("整图 Replay 按选定插件逐实例比较能力，不推断缺失声明")
class WorkflowReplayCapabilityServiceTest {

  @Mock
  private WorkerRegistryMapper workers;

  @InjectMocks
  private WorkflowReplayCapabilityService service;

  @Test
  @DisplayName("无关插件差异不影响选定插件的一致声明")
  void shouldCompareOnlySelectedPlugin_whenOtherCatalogueEntriesDiffer() {
    ReplayCapabilityDeclaration declared =
        declaration("2", IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC);
    String first =
        JsonUtils.toJson(List.of(declared, ReplayCapabilityDeclaration.unknown("other")));
    String second = JsonUtils.toJson(List.of(declared));
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            "tenant-a", "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of(first, second));

    var result = service.assess("tenant-a", "ATOMIC", "sql");

    assertThat(result.status()).isEqualTo(Status.DECLARED_CONSISTENT);
    assertThat(result.workerCount()).isEqualTo(2);
    assertThat(result.declaration()).isEqualTo(declared);
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "null", "{}", "invalid-json", "[null]"})
  @DisplayName("任一实例缺失或损坏的能力目录阻止重放")
  void shouldRejectCatalogue_whenAnyWorkerLacksValidDeclaration(String invalidCatalogue) {
    ReplayCapabilityDeclaration declared =
        declaration("2", IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC);
    String valid = JsonUtils.toJson(List.of(declared));
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            "tenant-a", "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of(valid, invalidCatalogue));

    var result = service.assess("tenant-a", "ATOMIC", "sql");

    assertThat(result.status()).isEqualTo(Status.MISSING_OR_AMBIGUOUS_DECLARATION);
  }

  @Test
  @DisplayName("相同插件在不同实例版本不一致时拒绝")
  void shouldRejectReplay_whenSelectedPluginVersionsDifferAcrossWorkers() {
    String first = JsonUtils.toJson(
        List.of(declaration("2", IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC)));
    String second = JsonUtils.toJson(
        List.of(declaration("3", IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC)));
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            "tenant-a", "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of(first, second));

    var result = service.assess("tenant-a", "ATOMIC", "sql");

    assertThat(result.status()).isEqualTo(Status.WORKER_DECLARATIONS_DIFFER);
  }

  @Test
  @DisplayName("明确非幂等插件即使声明完整仍不允许自动重放")
  void shouldRejectAutomaticReplay_whenPluginDeclaresNoIdempotency() {
    String catalogue = JsonUtils.toJson(List.of(declaration("2", IdempotencyCapability.NONE)));
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            "tenant-a", "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of(catalogue));

    var result = service.assess("tenant-a", "ATOMIC", "sql");

    assertThat(result.status()).isEqualTo(Status.REPLAY_SAFETY_UNPROVEN);
  }

  @Test
  @DisplayName("UNKNOWN 声明阻止自动重放")
  void shouldRejectAutomaticReplay_whenPluginSafetyIsUnknown() {
    String catalogue = JsonUtils.toJson(List.of(ReplayCapabilityDeclaration.unknown("sql")));
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            "tenant-a", "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of(catalogue));

    var result = service.assess("tenant-a", "ATOMIC", "sql");

    assertThat(result.status()).isEqualTo(Status.REPLAY_SAFETY_UNPROVEN);
  }

  @Test
  @DisplayName("同一实例重复声明选定插件时拒绝歧义目录")
  void shouldRejectCatalogue_whenSelectedPluginIsDeclaredTwice() {
    ReplayCapabilityDeclaration declared =
        declaration("2", IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC);
    String catalogue = JsonUtils.toJson(List.of(declared, declared));
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            "tenant-a", "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of(catalogue));

    var result = service.assess("tenant-a", "ATOMIC", "sql");

    assertThat(result.status()).isEqualTo(Status.MISSING_OR_AMBIGUOUS_DECLARATION);
  }

  @Test
  @DisplayName("没有在线实例时不会把空集当成所有实例通过")
  void shouldRejectReplay_whenNoOnlineWorkersExist() {
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            "tenant-a", "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of());

    var result = service.assess("tenant-a", "ATOMIC", "sql");

    assertThat(result.status()).isEqualTo(Status.NO_ONLINE_WORKERS);
  }

  @Test
  @DisplayName("插件身份未固定时不查询或猜测目录")
  void shouldRejectPluginSelection_whenIdentityIsMissing() {
    var result = service.assess("tenant-a", "ATOMIC", null);

    assertThat(result.status()).isEqualTo(Status.PLUGIN_SELECTION_NOT_FROZEN);
    verifyNoInteractions(workers);
  }

  @Test
  @DisplayName("数据库读取失败直接终止预览")
  void shouldPropagateFailure_whenCatalogueCannotBeRead() {
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            "tenant-a", "ATOMIC", List.of("ONLINE")))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    assertThatThrownBy(() -> service.assess("tenant-a", "ATOMIC", "sql"))
        .isInstanceOf(DataAccessResourceFailureException.class);
  }

  private ReplayCapabilityDeclaration declaration(
      String version, IdempotencyCapability capability) {
    return new ReplayCapabilityDeclaration(
        "sql",
        version,
        List.of("BUSINESS_DATABASE"),
        capability,
        "tenant+business-key",
        "SQL_TRANSACTION_V1",
        1);
  }
}
