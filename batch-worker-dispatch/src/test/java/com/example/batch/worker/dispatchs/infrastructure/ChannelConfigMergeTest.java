package com.example.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelConfigMergeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnEmptyForNullOrEmptyRow() {
        assertThat(ChannelConfigMerge.merge(null, objectMapper)).isEmpty();
        assertThat(ChannelConfigMerge.merge(Map.of(), objectMapper)).isEmpty();
    }

    @Test
    void shouldMergeConfigJsonMapAndProtectReservedKeys() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenant_id", "t1");
        row.put("endpoint", "http://legacy");
        Map<String, Object> cj = new LinkedHashMap<>();
        cj.put("endpoint", "http://override");
        cj.put("tenant_id", "should-not-override-column");
        cj.put("extra", "x");
        row.put("config_json", cj);

        Map<String, Object> merged = ChannelConfigMerge.merge(row, objectMapper);
        assertThat(merged.get("tenant_id")).isEqualTo("t1");
        assertThat(merged.get("endpoint")).isEqualTo("http://override");
        assertThat(merged.get("extra")).isEqualTo("x");
    }

    @Test
    void shouldParseConfigJsonStringWhenMapperProvided() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("config_json", "{\"timeoutMs\":5000,\"tenant_id\":\"ignored\"}");

        Map<String, Object> merged = ChannelConfigMerge.merge(row, objectMapper);
        assertThat(merged.get("timeoutMs")).isEqualTo(5000);
        assertThat(merged).doesNotContainKey("ignored");
    }
}
