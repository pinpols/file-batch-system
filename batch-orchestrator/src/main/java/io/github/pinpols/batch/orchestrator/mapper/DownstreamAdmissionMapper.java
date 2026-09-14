package io.github.pinpols.batch.orchestrator.mapper;

import java.util.Collection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DownstreamAdmissionMapper {

  boolean isAnyDispatchChannelBlocked(
      @Param("tenantId") String tenantId, @Param("channelCodes") Collection<String> channelCodes);
}
