package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.console.domain.entity.ConfigChangeLogEntity;
import io.github.pinpols.batch.console.domain.param.ConfigChangeLogInsertParam;
import io.github.pinpols.batch.console.domain.query.ConfigChangeLogQuery;
import java.util.List;

public interface ConfigChangeLogMapper {

  List<ConfigChangeLogEntity> selectByQuery(ConfigChangeLogQuery query);

  int insertConfigChangeLog(ConfigChangeLogInsertParam params);
}
