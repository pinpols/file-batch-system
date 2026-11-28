package io.github.pinpols.batch.console.domain.file.mapper;

import java.util.List;

import io.github.pinpols.batch.console.domain.file.entity.FileErrorRecordEntity;
import io.github.pinpols.batch.console.domain.file.query.FileErrorRecordQuery;

public interface FileErrorRecordMapper {

    List<FileErrorRecordEntity> selectByQuery(FileErrorRecordQuery query);

    long countByQuery(FileErrorRecordQuery query);
}
