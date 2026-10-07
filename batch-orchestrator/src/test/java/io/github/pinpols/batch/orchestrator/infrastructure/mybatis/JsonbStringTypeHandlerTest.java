package io.github.pinpols.batch.orchestrator.infrastructure.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("JSON 字符串类型处理器,覆盖按列名与列索引读取以及非空参数写入的包装行为")
class JsonbStringTypeHandlerTest {

  private final JsonbStringTypeHandler handler = new JsonbStringTypeHandler();

  @Test
  @DisplayName("按列名读取到非空 JSON 文本时返回包装结果,取值与数据库原始文本一致")
  void getNullableResult_returnsWrappedJsonbStringFromColumnName() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("payload")).thenReturn("{\"k\":1}");

    JsonbString result = handler.getNullableResult(rs, "payload");

    assertThat(result).isNotNull();
    assertThat(result.getValue()).isEqualTo("{\"k\":1}");
  }

  @Test
  @DisplayName("按列名读取到空值时直接返回空结果,不产生额外包装")
  void getNullableResult_columnNameNullValueReturnsNull() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("payload")).thenReturn(null);

    JsonbString result = handler.getNullableResult(rs, "payload");

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("按列索引读取到 JSON 数组文本时返回包装结果,取值与数据库原始文本一致")
  void getNullableResult_returnsWrappedJsonbStringFromColumnIndex() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString(7)).thenReturn("[]");

    JsonbString result = handler.getNullableResult(rs, 7);

    assertThat(result).isNotNull();
    assertThat(result.getValue()).isEqualTo("[]");
  }

  @Test
  @DisplayName("写入非空参数时改为绑定原始文本,写入内容与包装值一致")
  void setNonNullParameter_writesRawValueAsString() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    JsonbString value = JsonbString.of("{\"a\":\"b\"}");

    handler.setNonNullParameter(ps, 3, value, JdbcType.OTHER);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(ps).setString(org.mockito.Mockito.eq(3), captor.capture());
    assertThat(captor.getValue()).isEqualTo("{\"a\":\"b\"}");
  }
}
