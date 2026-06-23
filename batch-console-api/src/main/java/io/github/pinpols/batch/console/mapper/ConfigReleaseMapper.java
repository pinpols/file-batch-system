package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import io.github.pinpols.batch.console.domain.query.ConfigReleaseQuery;
import java.util.List;
import java.util.Map;

public interface ConfigReleaseMapper {

  List<ConfigReleaseEntity> selectByQuery(ConfigReleaseQuery query);

  ConfigReleaseEntity selectById(Map<String, Object> params);

  Integer selectLatestVersionNo(Map<String, Object> params);

  int insertConfigRelease(Map<String, Object> params);

  int updateConfigReleaseStatus(Map<String, Object> params);

  int updateGrayScope(Map<String, Object> params);
}
