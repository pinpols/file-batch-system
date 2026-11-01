package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.console.domain.job.query.PendingCatchUpQuery;
import io.github.pinpols.batch.console.domain.job.view.PendingCatchUpView;
import java.util.List;

public interface PendingCatchUpMapper {

  List<PendingCatchUpView> selectByQuery(PendingCatchUpQuery query);

  long countByQuery(PendingCatchUpQuery query);
}
