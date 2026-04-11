package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.PendingCatchUpEntity;
import com.example.batch.console.domain.query.PendingCatchUpQuery;

import java.util.List;

public interface PendingCatchUpMapper {

    List<PendingCatchUpEntity> selectByQuery(PendingCatchUpQuery query);

    long countByQuery(PendingCatchUpQuery query);
}
