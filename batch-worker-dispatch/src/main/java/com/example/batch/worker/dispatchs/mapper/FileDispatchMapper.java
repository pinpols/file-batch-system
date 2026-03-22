package com.example.batch.worker.dispatchs.mapper;

import java.util.Map;

public interface FileDispatchMapper {

    Map<String, Object> selectFileRecord(Map<String, Object> params);

    Map<String, Object> selectChannelConfig(Map<String, Object> params);

    Map<String, Object> selectLatestDispatchRecord(Map<String, Object> params);

    int insertDispatchRecord(Map<String, Object> params);

    int incrementAttempt(Map<String, Object> params);

    int markSent(Map<String, Object> params);

    int markAcked(Map<String, Object> params);

    int markFailed(Map<String, Object> params);

    int markCompensated(Map<String, Object> params);
}
