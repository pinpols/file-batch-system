package com.example.batch.console.domain.file.mapper;

import com.example.batch.console.domain.file.entity.FileErrorRecordEntity;
import com.example.batch.console.domain.file.query.FileErrorRecordQuery;
import java.util.List;

public interface FileErrorRecordMapper {

  List<FileErrorRecordEntity> selectByQuery(FileErrorRecordQuery query);

  long countByQuery(FileErrorRecordQuery query);
}
