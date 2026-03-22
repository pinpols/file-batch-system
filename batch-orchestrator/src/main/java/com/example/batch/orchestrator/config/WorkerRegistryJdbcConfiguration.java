package com.example.batch.orchestrator.config;

import java.util.List;
import java.sql.JDBCType;
import org.postgresql.util.PGobject;
import com.example.batch.orchestrator.domain.value.JsonbString;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.mapping.JdbcValue;

@Configuration
public class WorkerRegistryJdbcConfiguration {

    @Bean
    public JdbcCustomConversions jdbcCustomConversions() {
        return new JdbcCustomConversions(List.of(
                new JsonbStringToJdbcValueConverter(),
                new JsonbToStringConverter()
        ));
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
}
