package com.example.batch.console.domain.governance.mapper;

import com.example.batch.console.domain.governance.entity.DeadLetterTaskEntity;
import com.example.batch.console.domain.governance.query.DeadLetterTaskQuery;
import java.util.List;

public interface DeadLetterTaskMapper {

  List<DeadLetterTaskEntity> selectByQuery(DeadLetterTaskQuery query);

  long countByQuery(DeadLetterTaskQuery query);
}
