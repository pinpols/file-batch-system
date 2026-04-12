package com.example.batch.console.mapper;

import com.example.batch.console.mapper.query.FilePipelineQuery;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FilePipelineMapper {

  List<Map<String, Object>> selectByQuery(@Param("q") FilePipelineQuery q);

  long countByQuery(@Param("q") FilePipelineQuery q);

  long countByStatuses(
      @Param("tenantId") String tenantId, @Param("statuses") List<String> statuses);
}
