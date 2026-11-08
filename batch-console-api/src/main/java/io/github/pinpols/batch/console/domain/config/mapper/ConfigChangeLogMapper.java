package io.github.pinpols.batch.console.domain.config.mapper;

import io.github.pinpols.batch.console.domain.config.entity.ConfigChangeLogEntity;
import io.github.pinpols.batch.console.domain.config.param.ConfigChangeLogInsertParam;
import io.github.pinpols.batch.console.domain.config.query.ConfigChangeLogQuery;
import java.util.List;

public interface ConfigChangeLogMapper {

  List<ConfigChangeLogEntity> selectByQuery(ConfigChangeLogQuery query);

  int insertConfigChangeLog(ConfigChangeLogInsertParam params);
}
