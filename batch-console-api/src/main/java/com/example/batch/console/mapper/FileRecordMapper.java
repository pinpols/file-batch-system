package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.FileRecordEntity;
import com.example.batch.console.domain.query.FileRecordQuery;
import java.util.List;

public interface FileRecordMapper {

    List<FileRecordEntity> selectByQuery(FileRecordQuery query);
}
