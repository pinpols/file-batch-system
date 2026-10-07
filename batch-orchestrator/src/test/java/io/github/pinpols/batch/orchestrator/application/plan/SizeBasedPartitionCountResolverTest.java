package io.github.pinpols.batch.orchestrator.application.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.ShardStrategy;
import io.github.pinpols.batch.orchestrator.config.PersistenceGranularityProperties;
import jakarta.validation.Validation;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("按大小切分数量解析器: 目标条数优先, 分档策略与参数校验口径")
class SizeBasedPartitionCountResolverTest {

  @Test
  @DisplayName("显式目标条数优先于分档策略, 按总条数除以目标条数得到分区数")
  void shouldKeepExplicitTarget_whenTierPolicyEnabled() {
    SizeBasedPartitionCountResolver resolver = resolver(true);

    int count = resolver.resolve(
        null,
        Map.of("estimatedItemCount", 1_000_000L, "targetItemsPerPartition", 100_000L),
        ShardStrategy.DYNAMIC);

    assertThat(count).isEqualTo(10);
  }

  @Test
  @DisplayName("分档策略关闭时沿用旧的回退逻辑, 解析结果为零")
  void shouldKeepLegacyFallback_whenPolicyDisabled() {
    SizeBasedPartitionCountResolver resolver = resolver(false);

    assertThat(
            resolver.resolve(null, Map.of("estimatedItemCount", 1_000_000L), ShardStrategy.DYNAMIC))
        .isZero();
  }

  @Test
  @DisplayName("启用分档策略时按条数档位分别解析出对应的分区数")
  void shouldUseItemTiers_whenPolicyEnabled() {
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
  @DisplayName("缺少条数估算时改按文件大小档位解析分区数")
  void shouldUseByteTier_whenItemEstimateMissing() {
    SizeBasedPartitionCountResolver resolver = resolver(true);

    assertThat(resolver.resolve(
            null, Map.of("estimatedFileSizeBytes", 128L * 1024 * 1024), ShardStrategy.AUTO))
        .isEqualTo(1);
    assertThat(resolver.resolve(
            null, Map.of("estimatedFileSizeBytes", 2L * 1024 * 1024 * 1024), ShardStrategy.AUTO))
        .isEqualTo(4);
  }

  @Test
  @DisplayName("分档阈值配置非法时被参数校验拒绝并指出字段名")
  void shouldRejectInvalidTierConfig_whenBeanValidationRuns() {
    PersistenceGranularityProperties properties = new PersistenceGranularityProperties();
    properties.setLargeTargetItems(0);

    try (var factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(properties))
          .extracting(violation -> violation.getPropertyPath().toString())
          .containsExactly("largeTargetItems");
    }
  }

  private SizeBasedPartitionCountResolver resolver(boolean enabled) {
    PersistenceGranularityProperties properties = new PersistenceGranularityProperties();
    properties.setEnabled(enabled);
    return new SizeBasedPartitionCountResolver(properties);
  }
}
