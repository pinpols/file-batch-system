package io.github.pinpols.batch.orchestrator.application.plan;

import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ShardStrategy;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * ADR-046 文件束:束作业({@code BUNDLE_IMPORT}/{@code BUNDLE_EXPORT}/{@code BUNDLE_DISPATCH})的分区数 =
 * 提交清单里的绑定项数。
 *
 * <p>放在解析器链最前({@code @Order(0)},优先于 Explicit),因为束的分区数是「一项一 partition」的硬语义,不该被显式 {@code
 * partitionCount} 或容量估算覆盖。非束作业返回 0,交回后续 resolver(不影响存量四策略)。
 *
 * <p>本实现的新增经 ADR-046 批准(满足 {@link PartitionCountResolver} 的「新增需 ADR 批准」扩展点约定);纯函数无副作用。
 */
@Component
@Order(0)
public class BundlePartitionCountResolver implements PartitionCountResolver {

  @Override
  public int resolve(
      JobDefinitionEntity jobDefinition, Map<String, Object> params, ShardStrategy shardStrategy) {
    if (jobDefinition == null) {
      return 0;
    }
    JobType bundleType = DictEnum.fromCode(JobType.class, jobDefinition.jobType());
    if (bundleType == null || !bundleType.isBundle()) {
      return 0;
    }
    return BundlePlanParams.extract(params, bundleType).size();
  }
}
