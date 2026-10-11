package io.github.pinpols.batch.worker.dispatchs.mapper;

import org.apache.ibatis.annotations.Param;

public interface DispatchSecretVersionMapper {

  String selectPayloadForDispatch(
      @Param("tenantId") String tenantId, @Param("secretVersionId") Long secretVersionId);
}
