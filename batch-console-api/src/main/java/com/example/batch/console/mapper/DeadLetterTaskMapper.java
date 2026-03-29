package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.DeadLetterTaskEntity;
import com.example.batch.console.domain.query.DeadLetterTaskQuery;
import java.util.List;

public interface DeadLetterTaskMapper {

    List<DeadLetterTaskEntity> selectByQuery(DeadLetterTaskQuery query);

    long countByQuery(DeadLetterTaskQuery query);
}
