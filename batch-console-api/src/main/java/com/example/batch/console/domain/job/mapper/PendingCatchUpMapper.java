package com.example.batch.console.domain.job.mapper;

import com.example.batch.console.domain.job.entity.PendingCatchUpEntity;
import com.example.batch.console.domain.job.query.PendingCatchUpQuery;
import java.util.List;

public interface PendingCatchUpMapper {

  List<PendingCatchUpEntity> selectByQuery(PendingCatchUpQuery query);

  long countByQuery(PendingCatchUpQuery query);
}
