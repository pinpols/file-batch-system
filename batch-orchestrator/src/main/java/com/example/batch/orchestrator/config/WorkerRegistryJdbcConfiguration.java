package com.example.batch.orchestrator.config;

import com.example.batch.orchestrator.domain.value.JsonbString;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.mapping.JdbcValue;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

import java.sql.JDBCType;
import java.util.List;
import java.util.Map;

@Configuration
public class WorkerRegistryJdbcConfiguration extends AbstractJdbcConfiguration {

    @Override
    protected List<?> userConverters() {
        return List.of(
                new JsonbStringToJdbcValueConverter(),
                new JsonbToStringConverter(),
                new JsonbToMapConverter());
    }

    @WritingConverter
    static class JsonbStringToJdbcValueConverter implements Converter<JsonbString, JdbcValue> {

        @Override
        public JdbcValue convert(JsonbString source) {
            if (source == null) {
                return null;
            }
            return JdbcValue.of(source.getValue(), JDBCType.OTHER);
        }
    }

    @ReadingConverter
    static class JsonbToStringConverter implements Converter<PGobject, JsonbString> {

        @Override
        public JsonbString convert(PGobject source) {
            return source == null ? null : JsonbString.of(source.getValue());
        }
    }

    @ReadingConverter
    static class JsonbToMapConverter implements Converter<PGobject, Map<String, Object>> {

        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

        @Override
        public Map<String, Object> convert(PGobject source) {
            if (source == null || source.getValue() == null) {
                return null;
            }
            try {
                return MAPPER.readValue(source.getValue(), MAP_TYPE);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Failed to parse jsonb to Map: " + source.getValue(), e);
            }
        }
    }
}
