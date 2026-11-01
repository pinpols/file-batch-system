package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.view.DispatchSecretBindingView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DispatchSecretBindingMapper {

  List<DispatchSecretBindingView> selectCurrentByChannels(
      @Param("tenantId") String tenantId, @Param("channelCodes") List<String> channelCodes);
}
