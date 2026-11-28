package io.github.pinpols.batch.console.domain.config.mapper;

import java.util.List;

import io.github.pinpols.batch.console.domain.config.entity.ConfigChangeLogEntity;
import io.github.pinpols.batch.console.domain.config.param.ConfigChangeLogInsertParam;
import io.github.pinpols.batch.console.domain.config.query.ConfigChangeLogQuery;

public interface ConfigChangeLogMapper {

    List<ConfigChangeLogEntity> selectByQuery(ConfigChangeLogQuery query);

    int insertConfigChangeLog(ConfigChangeLogInsertParam params);
}
