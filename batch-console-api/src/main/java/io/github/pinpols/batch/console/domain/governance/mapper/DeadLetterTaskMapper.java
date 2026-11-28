package io.github.pinpols.batch.console.domain.governance.mapper;

import java.util.List;

import io.github.pinpols.batch.console.domain.governance.entity.DeadLetterTaskEntity;
import io.github.pinpols.batch.console.domain.governance.query.DeadLetterTaskQuery;

public interface DeadLetterTaskMapper {

    List<DeadLetterTaskEntity> selectByQuery(DeadLetterTaskQuery query);

    long countByQuery(DeadLetterTaskQuery query);
}
