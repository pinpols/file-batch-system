package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.console.domain.config.param.ConfigSyncLogInsertParam;
import io.github.pinpols.batch.console.domain.config.param.ConfigSyncLogUpdateParam;
import io.github.pinpols.batch.console.domain.config.view.ConfigSyncLogRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ConfigSyncLogMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  List<ConfigSyncLogRow> selectByTenant(
      @Param("tenantId") String tenantId, @Param("offset") long offset, @Param("limit") int limit);

  ConfigSyncLogRow selectByIdForUpdate(
      @Param("tenantId") String tenantId, @Param("logId") Long logId);

  ConfigSyncLogRow selectByRequestKey(
      @Param("tenantId") String tenantId, @Param("requestKeyHash") String requestKeyHash);

  Long selectIdByRequestKey(
      @Param("tenantId") String tenantId, @Param("requestKeyHash") String requestKeyHash);

  long countByTenant(@Param("tenantId") String tenantId);

  int insert(ConfigSyncLogInsertParam params);

  int updateResult(ConfigSyncLogUpdateParam params);
}
