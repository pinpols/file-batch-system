package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 维护状态共享事实源的 MyBatis 映射。 */
@Mapper
public interface MaintenanceStateMapper {

  MaintenanceStateEntity selectSingleton();

  int updateIfVersion(
      @Param("enabled") boolean enabled,
      @Param("readOnly") boolean readOnly,
      @Param("message") String message,
      @Param("etaAt") java.time.Instant etaAt,
      @Param("affectedServicesJson") String affectedServicesJson,
      @Param("updatedBy") String updatedBy,
      @Param("expectedVersion") long expectedVersion);
}
