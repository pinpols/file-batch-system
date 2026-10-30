package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import io.github.pinpols.batch.console.domain.param.ConfigReleaseInsertParam;
import io.github.pinpols.batch.console.domain.param.ConfigReleaseStatusUpdateParam;
import io.github.pinpols.batch.console.domain.query.ConfigReleaseQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ConfigReleaseMapper {

  List<ConfigReleaseEntity> selectByQuery(ConfigReleaseQuery query);

  ConfigReleaseEntity selectById(
      @Param("tenantId") String tenantId, @Param("releaseId") Long releaseId);

  Integer selectLatestVersionNo(
      @Param("tenantId") String tenantId,
      @Param("configType") String configType,
      @Param("configKey") String configKey);

  int acquireVersionLock(
      @Param("tenantId") String tenantId,
      @Param("configType") String configType,
      @Param("configKey") String configKey);

  ConfigReleaseEntity selectPreviousEffective(
      @Param("tenantId") String tenantId,
      @Param("configType") String configType,
      @Param("configKey") String configKey,
      @Param("versionNo") Integer versionNo);

  int insertConfigRelease(ConfigReleaseInsertParam params);

  int updateConfigReleaseStatus(ConfigReleaseStatusUpdateParam params);
}
