package io.github.pinpols.batch.orchestrator.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 以原生 JSON 读取单个租户的分发通道配置。 */
@Mapper
public interface DispatchChannelSnapshotMapper {

  String selectCurrentChannelSnapshotJson(
      @Param("tenantId") String tenantId, @Param("channelCode") String channelCode);
}
