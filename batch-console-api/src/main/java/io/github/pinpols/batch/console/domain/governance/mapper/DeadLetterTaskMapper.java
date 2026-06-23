package io.github.pinpols.batch.console.domain.governance.mapper;

import io.github.pinpols.batch.console.domain.governance.entity.DeadLetterTaskEntity;
import io.github.pinpols.batch.console.domain.governance.query.DeadLetterTaskQuery;
import java.util.List;

public interface DeadLetterTaskMapper {

  List<DeadLetterTaskEntity> selectByQuery(DeadLetterTaskQuery query);

  long countByQuery(DeadLetterTaskQuery query);
}
