package io.github.pinpols.batch.common.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayCapabilityDeclarationTest {

  @Test
  void unknownDeclarationIsExplicitAndNeverFullyDeclared() {
    ReplayCapabilityDeclaration declaration = ReplayCapabilityDeclaration.unknown("plugin-x");

    assertThat(declaration.pluginId()).isEqualTo("plugin-x");
    assertThat(declaration.idempotencyCapability()).isEqualTo(IdempotencyCapability.UNKNOWN);
    assertThat(declaration.externalEffectCategories()).containsExactly("UNKNOWN");
    assertThat(declaration.isFullyDeclared()).isFalse();
  }

  @Test
  void declarationCopiesEffectCategories() {
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

  @Test
  void rejectsUnsupportedContractVersion() {
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

  @Test
  void staticDeclarationCoversRuntimeSubsetWithSameRecoveryContract() {
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

  @Test
  void staticDeclarationRejectsRuntimeCapabilityOutsideItsContract() {
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

  @Test
  void staticDeclarationDoesNotCoverUnknownOrNonIdempotentRuntimeCapability() {
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

    assertThat(conservative.covers(ReplayCapabilityDeclaration.unknown("plugin-x"))).isFalse();
    assertThat(conservative.covers(nonIdempotent)).isFalse();
  }

  @Test
  void replayExecutionGuardRequiresPositiveOperationAndReplayableDeclaration() {
    ReplayCapabilityDeclaration unknown = ReplayCapabilityDeclaration.unknown("plugin-x");

    assertThatThrownBy(() -> new ReplayExecutionGuard(0L, declaration(
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
