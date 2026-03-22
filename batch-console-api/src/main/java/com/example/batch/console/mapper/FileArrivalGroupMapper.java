package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.FileArrivalGroupEntity;
import com.example.batch.console.domain.query.FileArrivalGroupQuery;
import java.util.List;

public interface FileArrivalGroupMapper {

    List<FileArrivalGroupEntity> selectByQuery(FileArrivalGroupQuery query);
}
