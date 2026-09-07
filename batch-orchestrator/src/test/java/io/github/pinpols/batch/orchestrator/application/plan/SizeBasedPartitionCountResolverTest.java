package io.github.pinpols.batch.orchestrator.application.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.ShardStrategy;
import io.github.pinpols.batch.orchestrator.config.PersistenceGranularityProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SizeBasedPartitionCountResolverTest {

  @Test
  void explicitTargetKeepsPriorityWhenTierPolicyIsEnabled() {
    SizeBasedPartitionCountResolver resolver = resolver(true);

    int count = resolver.resolve(
        null,
        Map.of("estimatedItemCount", 1_000_000L, "targetItemsPerPartition", 100_000L),
        ShardStrategy.DYNAMIC);

    assertThat(count).isEqualTo(10);
  }

  @Test
  void disabledPolicyKeepsLegacyFallback() {
    SizeBasedPartitionCountResolver resolver = resolver(false);

    assertThat(
            resolver.resolve(null, Map.of("estimatedItemCount", 1_000_000L), ShardStrategy.DYNAMIC))
        .isZero();
  }

  @Test
  void enabledPolicyUsesCompactStandardAndLargeItemTiers() {
    SizeBasedPartitionCountResolver resolver = resolver(true);

    assertThat(
            resolver.resolve(null, Map.of("estimatedItemCount", 100_000L), ShardStrategy.DYNAMIC))
        .isEqualTo(1);
    assertThat(
            resolver.resolve(null, Map.of("estimatedItemCount", 3_000_000L), ShardStrategy.DYNAMIC))
        .isEqualTo(3);
    assertThat(resolver.resolve(
            null, Map.of("estimatedItemCount", 12_000_000L), ShardStrategy.DYNAMIC))
        .isEqualTo(24);
  }

  @Test
  void enabledPolicyUsesByteTierWhenItemEstimateIsMissing() {
    SizeBasedPartitionCountResolver resolver = resolver(true);

    assertThat(resolver.resolve(
            null, Map.of("estimatedFileSizeBytes", 128L * 1024 * 1024), ShardStrategy.AUTO))
        .isEqualTo(1);
    assertThat(resolver.resolve(
            null, Map.of("estimatedFileSizeBytes", 2L * 1024 * 1024 * 1024), ShardStrategy.AUTO))
        .isEqualTo(4);
  }

  private SizeBasedPartitionCountResolver resolver(boolean enabled) {
    PersistenceGranularityProperties properties = new PersistenceGranularityProperties();
    properties.setEnabled(enabled);
    return new SizeBasedPartitionCountResolver(properties);
  }
}
