package io.github.pinpols.batch.orchestrator.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Reads one tenant-scoped dispatch-channel configuration as native JSON. */
@Mapper
public interface DispatchChannelSnapshotMapper {

  String selectCurrentChannelSnapshotJson(
      @Param("tenantId") String tenantId, @Param("channelCode") String channelCode);
}
