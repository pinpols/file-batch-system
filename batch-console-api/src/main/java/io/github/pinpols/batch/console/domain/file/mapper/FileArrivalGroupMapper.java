package io.github.pinpols.batch.console.domain.file.mapper;

import java.util.List;

import io.github.pinpols.batch.console.domain.file.query.FileArrivalGroupQuery;
import io.github.pinpols.batch.console.domain.file.view.FileArrivalGroupView;

public interface FileArrivalGroupMapper {

    List<FileArrivalGroupView> selectByQuery(FileArrivalGroupQuery query);

    long countByQuery(FileArrivalGroupQuery query);
}
