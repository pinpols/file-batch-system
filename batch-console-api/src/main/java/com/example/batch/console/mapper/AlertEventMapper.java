package com.example.batch.console.mapper;

import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.console.domain.query.AlertEventQuery;
import java.util.List;

public interface AlertEventMapper {

    List<AlertEventEntity> selectByQuery(AlertEventQuery query);
}
