package com.example.batch.worker.dispatchs.mapper;

import java.util.List;
import java.util.Map;

/** 文件分发 MyBatis Mapper 接口，对应 file_dispatch_record 及 file_channel_config 相关 SQL 操作。 */
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

  List<Map<String, Object>> listPendingReceiptPolls(Map<String, Object> params);
}
