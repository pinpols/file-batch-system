package com.example.batch.console.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FileDispatchRecordMapper {

    List<Map<String, Object>> selectByQuery(@Param("tenantId") String tenantId,
                                            @Param("fileId") Long fileId,
                                            @Param("channelCode") String channelCode,
                                            @Param("dispatchStatus") String dispatchStatus,
                                            @Param("receiptStatus") String receiptStatus,
                                            @Param("fromTime") Instant fromTime,
                                            @Param("toTime") Instant toTime);
}
