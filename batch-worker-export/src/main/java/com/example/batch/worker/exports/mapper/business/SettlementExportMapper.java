package com.example.batch.worker.exports.mapper.business;

import java.util.List;
import java.util.Map;

public interface SettlementExportMapper {

    Map<String, Object> selectBatch(Map<String, Object> params);

    List<Map<String, Object>> selectDetailsByBatchIdAfterId(Map<String, Object> params);

    int updateBatchExported(Map<String, Object> params);

    int updateDetailsExported(Map<String, Object> params);
}
