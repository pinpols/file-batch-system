package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.entity.FileArrivalGroupEntity;
import io.github.pinpols.batch.console.domain.file.query.FileArrivalGroupQuery;
import java.util.List;

public interface FileArrivalGroupMapper {

  List<FileArrivalGroupEntity> selectByQuery(FileArrivalGroupQuery query);

  long countByQuery(FileArrivalGroupQuery query);
}
