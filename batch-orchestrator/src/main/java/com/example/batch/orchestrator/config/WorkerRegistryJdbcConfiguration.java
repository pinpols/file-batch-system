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
import org.springframework.data.jdbc.core.dialect.DialectResolver;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.mapping.JdbcValue;
import org.springframework.data.jdbc.repository.config.JdbcConfiguration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

@Configuration
public class WorkerRegistryJdbcConfiguration {

    @Bean
    public JdbcCustomConversions jdbcCustomConversions(NamedParameterJdbcOperations operations) {
        // 与 AbstractJdbcConfiguration 相同工厂路径：按 DataSource 解析 JdbcDialect（含 PGobject 等 JDBC 简单类型），消除 PGobject 读转换 WARN。
        JdbcDialect dialect = DialectResolver.getDialect(operations.getJdbcOperations());
        return JdbcConfiguration.createCustomConversions(dialect, List.of(
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
