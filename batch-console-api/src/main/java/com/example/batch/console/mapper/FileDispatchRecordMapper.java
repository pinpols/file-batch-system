package com.example.batch.console.mapper;

import com.example.batch.console.mapper.query.FileDispatchRecordQuery;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FileDispatchRecordMapper {

  List<Map<String, Object>> selectByQuery(@Param("q") FileDispatchRecordQuery q);

  long countByQuery(@Param("q") FileDispatchRecordQuery q);
}
