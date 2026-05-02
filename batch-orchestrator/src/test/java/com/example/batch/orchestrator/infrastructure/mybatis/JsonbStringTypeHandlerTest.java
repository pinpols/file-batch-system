package com.example.batch.orchestrator.infrastructure.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.domain.value.JsonbString;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JsonbStringTypeHandlerTest {

  private final JsonbStringTypeHandler handler = new JsonbStringTypeHandler();

  @Test
  void getNullableResult_returnsWrappedJsonbStringFromColumnName() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("payload")).thenReturn("{\"k\":1}");

    JsonbString result = handler.getNullableResult(rs, "payload");

    assertThat(result).isNotNull();
    assertThat(result.getValue()).isEqualTo("{\"k\":1}");
  }

  @Test
  void getNullableResult_columnNameNullValueReturnsNull() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("payload")).thenReturn(null);

    JsonbString result = handler.getNullableResult(rs, "payload");

    assertThat(result).isNull();
  }

  @Test
  void getNullableResult_returnsWrappedJsonbStringFromColumnIndex() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString(7)).thenReturn("[]");

    JsonbString result = handler.getNullableResult(rs, 7);

    assertThat(result).isNotNull();
    assertThat(result.getValue()).isEqualTo("[]");
  }

  @Test
  void setNonNullParameter_writesRawValueAsString() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    JsonbString value = JsonbString.of("{\"a\":\"b\"}");

    handler.setNonNullParameter(ps, 3, value, JdbcType.OTHER);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(ps).setString(org.mockito.Mockito.eq(3), captor.capture());
    assertThat(captor.getValue()).isEqualTo("{\"a\":\"b\"}");
  }
}
