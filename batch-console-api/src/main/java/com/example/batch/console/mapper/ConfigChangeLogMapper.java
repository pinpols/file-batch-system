package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.ConfigChangeLogEntity;
import com.example.batch.console.domain.query.ConfigChangeLogQuery;
import java.util.List;
import java.util.Map;

public interface ConfigChangeLogMapper {

    List<ConfigChangeLogEntity> selectByQuery(ConfigChangeLogQuery query);

    int insertConfigChangeLog(Map<String, Object> params);
}
