package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.entity.FileErrorRecordEntity;
import io.github.pinpols.batch.console.domain.file.query.FileErrorRecordQuery;
import java.util.List;

public interface FileErrorRecordMapper {

  List<FileErrorRecordEntity> selectByQuery(FileErrorRecordQuery query);

  long countByQuery(FileErrorRecordQuery query);
}
