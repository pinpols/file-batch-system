package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.query.FilePipelineQuery;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineQueryView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FilePipelineMapper {

  List<FilePipelineQueryView> selectByQuery(@Param("q") FilePipelineQuery q);

  long countByQuery(@Param("q") FilePipelineQuery q);

  long countByStatuses(
      @Param("tenantId") String tenantId, @Param("statuses") List<String> statuses);
}
