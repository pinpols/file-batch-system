package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.console.domain.entity.ConfigChangeLogEntity;
import io.github.pinpols.batch.console.domain.query.ConfigChangeLogQuery;
import java.util.List;
import java.util.Map;

public interface ConfigChangeLogMapper {

  List<ConfigChangeLogEntity> selectByQuery(ConfigChangeLogQuery query);

  int insertConfigChangeLog(Map<String, Object> params);
}
