package com.example.batch.console.mapper;

import com.example.batch.console.domain.query.OutboxRetryLogQuery;
import java.util.List;
import java.util.Map;

public interface OutboxRetryLogMapper {

    List<Map<String, Object>> selectByQuery(OutboxRetryLogQuery query);
}
