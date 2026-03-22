package com.example.batch.console.mapper;

import com.example.batch.console.domain.query.OutboxDeliveryLogQuery;
import java.util.List;
import java.util.Map;

public interface OutboxDeliveryLogMapper {

    List<Map<String, Object>> selectByQuery(OutboxDeliveryLogQuery query);
}
