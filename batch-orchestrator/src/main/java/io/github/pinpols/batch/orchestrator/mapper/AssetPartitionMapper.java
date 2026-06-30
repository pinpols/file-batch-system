package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionMaterializationCommand;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionSnapshot;
import org.apache.ibatis.annotations.Param;

/** BFS 最小资产分区读模型映射。 */
public interface AssetPartitionMapper {

  int upsertDataAsset(
      @Param("tenantId") String tenantId,
      @Param("assetCode") String assetCode,
      @Param("assetType") String assetType,
      @Param("displayName") String displayName,
      @Param("ownerJobCode") String ownerJobCode);

  Long selectDataAssetId(
      @Param("tenantId") String tenantId,
      @Param("assetCode") String assetCode,
      @Param("assetType") String assetType);

  int upsertEffectiveJobPartition(AssetPartitionMaterializationCommand command);

  AssetPartitionSnapshot selectEffectiveJobPartition(
      @Param("tenantId") String tenantId,
      @Param("assetCode") String assetCode,
      @Param("partitionKey") String partitionKey);
}
