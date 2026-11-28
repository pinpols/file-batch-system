package io.github.pinpols.batch.console.domain.file.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.console.domain.file.view.PipelineProgressDirtyView;

/** Console pipeline 进度脏数据扫描 Mapper。 */
public interface ConsolePipelineProgressDirtyMapper {

    List<PipelineProgressDirtyView> selectUpdatedSince(
            @Param("since") Instant since, @Param("after") DirtyCursor after, @Param("limit") int limit);

    record DirtyCursor(Instant updatedAt, String tenantId, Long pipelineInstanceId) {}
}
