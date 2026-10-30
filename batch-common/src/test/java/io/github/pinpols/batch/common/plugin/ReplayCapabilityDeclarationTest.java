package io.github.pinpols.batch.common.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Replay 能力声明的覆盖关系与执行约束")
class ReplayCapabilityDeclarationTest {

  @DisplayName("未知能力保持显式且不能视为完整声明")
  @Test
  void shouldKeepCapabilityUnknown_whenDeclarationIncomplete() {
    ReplayCapabilityDeclaration declaration = ReplayCapabilityDeclaration.unknown("plugin-x");

    assertThat(declaration.pluginId()).isEqualTo("plugin-x");
    assertThat(declaration.idempotencyCapability()).isEqualTo(IdempotencyCapability.UNKNOWN);
    assertThat(declaration.externalEffectCategories()).containsExactly("UNKNOWN");
    assertThat(declaration.isFullyDeclared()).isFalse();
  }

  @DisplayName("声明复制副作用类别以避免外部修改")
  @Test
  void shouldCopyEffectCategories_whenDeclarationCreated() {
    List<String> effects = new ArrayList<>(List.of("DATABASE_WRITE"));
    ReplayCapabilityDeclaration declaration = new ReplayCapabilityDeclaration(
        "plugin-x",
        "1.2.3",
        effects,
        IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT,
        "tenant+business-key",
        "database-unique-constraint",
        ReplayCapabilityDeclaration.CURRENT_CONTRACT_VERSION);

    effects.add("HTTP_SEND");

    assertThat(declaration.externalEffectCategories()).containsExactly("DATABASE_WRITE");
    assertThat(declaration.isFullyDeclared()).isTrue();
  }

  @DisplayName("拒绝不支持的能力契约版本")
  @Test
  void shouldRejectVersion_whenContractUnsupported() {
    assertThatThrownBy(() -> new ReplayCapabilityDeclaration(
            "plugin-x",
            "1.2.3",
            List.of("DATABASE_WRITE"),
            IdempotencyCapability.NONE,
            "NONE",
            "manual-reconciliation",
            2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported replay capability contract version");
  }

  @DisplayName("恢复契约一致时静态声明覆盖运行能力子集")
  @Test
  void shouldCoverRuntimeSubset_whenRecoveryContractMatches() {
    ReplayCapabilityDeclaration conservative = declaration(
        List.of("DATABASE_WRITE", "OBJECT_STORE_WRITE"),
        IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC,
        "tenant+business-key",
        "plugin-ledger-v1");
    ReplayCapabilityDeclaration actual = declaration(
        List.of("DATABASE_WRITE"),
        IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC,
        "tenant+business-key",
        "plugin-ledger-v1");

    assertThat(conservative.covers(actual)).isTrue();
  }

  @DisplayName("拒绝超出静态声明契约的运行能力")
  @Test
  void shouldRejectRuntimeCapability_whenOutsideStaticContract() {
    ReplayCapabilityDeclaration conservative = declaration(
        List.of("DATABASE_WRITE"),
        IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT,
        "tenant+business-key",
        "database-unique-constraint");
    ReplayCapabilityDeclaration actual = declaration(
        List.of("DATABASE_WRITE", "HTTP_SEND"),
        IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC,
        "global-request-id",
        "remote-idempotency-key");

    assertThat(conservative.covers(actual)).isFalse();
  }

  @DisplayName("静态声明不覆盖未知或非幂等运行能力")
  @Test
  void shouldRejectCoverage_whenRuntimeCapabilityUnknownOrNonIdempotent() {
    ReplayCapabilityDeclaration conservative = declaration(
        List.of("DATABASE_WRITE"),
        IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT,
        "tenant+business-key",
        "database-unique-constraint");
    ReplayCapabilityDeclaration nonIdempotent = declaration(
        List.of("DATABASE_WRITE"),
        IdempotencyCapability.NONE,
        "tenant+business-key",
        "manual-reconciliation");

    assertThat(conservative.covers(ReplayCapabilityDeclaration.unknown("plugin-x")))
        .isFalse();
    assertThat(conservative.covers(nonIdempotent)).isFalse();
  }

  @DisplayName("执行守护要求有效操作编号与可重放声明")
  @Test
  void shouldRequirePositiveOperationAndCapability_whenCreatingReplayGuard() {
    ReplayCapabilityDeclaration unknown = ReplayCapabilityDeclaration.unknown("plugin-x");

    assertThatThrownBy(() -> new ReplayExecutionGuard(
            0L,
            declaration(
                List.of("DATABASE_WRITE"),
                IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT,
                "tenant+business-key",
                "database-unique-constraint")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("replayOperationId must be positive");
    assertThatThrownBy(() -> new ReplayExecutionGuard(7L, unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete and replayable");
  }

  private static ReplayCapabilityDeclaration declaration(
      List<String> effects,
      IdempotencyCapability idempotency,
      String keyScope,
      String recoveryProtocol) {
    return new ReplayCapabilityDeclaration(
        "plugin-x",
        "1.2.3",
        effects,
        idempotency,
        keyScope,
        recoveryProtocol,
        ReplayCapabilityDeclaration.CURRENT_CONTRACT_VERSION);
  }
}
