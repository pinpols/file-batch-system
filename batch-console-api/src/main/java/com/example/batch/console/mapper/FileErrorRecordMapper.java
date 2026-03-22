package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.FileErrorRecordEntity;
import com.example.batch.console.domain.query.FileErrorRecordQuery;
import java.util.List;

public interface FileErrorRecordMapper {

    List<FileErrorRecordEntity> selectByQuery(FileErrorRecordQuery query);
}
