package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.query.FileDispatchRecordQuery;
import io.github.pinpols.batch.console.domain.file.view.FileDispatchRecordQueryView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FileDispatchRecordMapper {

  List<FileDispatchRecordQueryView> selectByQuery(@Param("q") FileDispatchRecordQuery q);

  long countByQuery(@Param("q") FileDispatchRecordQuery q);
}
