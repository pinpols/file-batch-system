package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.DispatchSecretBindingEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DispatchSecretBindingMapper {

  List<DispatchSecretBindingEntity> selectCurrentByChannels(
      @Param("tenantId") String tenantId, @Param("channelCodes") List<String> channelCodes);
}
