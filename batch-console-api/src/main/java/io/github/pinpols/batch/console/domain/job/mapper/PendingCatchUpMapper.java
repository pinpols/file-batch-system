package io.github.pinpols.batch.console.domain.job.mapper;

import java.util.List;

import io.github.pinpols.batch.console.domain.job.query.PendingCatchUpQuery;
import io.github.pinpols.batch.console.domain.job.view.PendingCatchUpView;

public interface PendingCatchUpMapper {

    List<PendingCatchUpView> selectByQuery(PendingCatchUpQuery query);

    long countByQuery(PendingCatchUpQuery query);
}
