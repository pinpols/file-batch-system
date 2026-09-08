package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.view.PipelineProgressDirtyView;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Console pipeline 进度脏数据扫描 Mapper。 */
public interface ConsolePipelineProgressDirtyMapper {

  List<PipelineProgressDirtyView> selectUpdatedSince(
      @Param("since") Instant since, @Param("limit") int limit);
}
