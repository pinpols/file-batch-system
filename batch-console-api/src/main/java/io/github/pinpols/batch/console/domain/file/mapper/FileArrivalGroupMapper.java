package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.query.FileArrivalGroupQuery;
import io.github.pinpols.batch.console.domain.file.view.FileArrivalGroupView;
import java.util.List;

public interface FileArrivalGroupMapper {

  List<FileArrivalGroupView> selectByQuery(FileArrivalGroupQuery query);

  long countByQuery(FileArrivalGroupQuery query);
}
