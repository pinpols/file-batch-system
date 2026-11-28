package io.github.pinpols.batch.console.domain.file.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.console.domain.file.query.FilePipelineQuery;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineQueryView;

public interface FilePipelineMapper {

    List<FilePipelineQueryView> selectByQuery(@Param("q") FilePipelineQuery q);

    long countByQuery(@Param("q") FilePipelineQuery q);

    long countByStatuses(@Param("tenantId") String tenantId, @Param("statuses") List<String> statuses);
}
