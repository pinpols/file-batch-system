package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.console.domain.ops.param.MaintenanceStateUpdateParam;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 维护状态共享事实源的 MyBatis 映射。 */
@Mapper
public interface MaintenanceStateMapper {

  MaintenanceStateEntity selectSingleton();

  int updateIfVersion(@Param("param") MaintenanceStateUpdateParam param);
}
