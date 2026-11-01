package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.console.application.config.ConfigCacheInvalidationIntent;
import org.apache.ibatis.annotations.Param;

/** 配置缓存失效意图的领取与确认。 */
public interface ConfigCacheInvalidationOutboxMapper {
  ConfigCacheInvalidationIntent lockNext();

  int claim(@Param("id") long id, @Param("token") String token);

  int acknowledge(@Param("id") long id, @Param("token") String token);

  int retry(@Param("id") long id, @Param("token") String token);

  int insert(@Param("tenantId") String tenantId, @Param("type") String type,
      @Param("code") String code);
}
