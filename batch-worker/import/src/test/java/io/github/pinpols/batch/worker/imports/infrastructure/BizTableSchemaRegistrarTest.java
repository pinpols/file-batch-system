package io.github.pinpols.batch.worker.imports.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.core.mapper.BizTableSchemaMapper;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

/** {@link BizTableSchemaRegistrar} 单元测试:覆盖元数据读取成功后覆盖写,以及读取失败保留旧快照。 */
@DisplayName("业务库表结构登记单测:成功覆盖写与读取失败不刷新")
class BizTableSchemaRegistrarTest {

  private DataSource dataSource;
  private Connection connection;
  private DatabaseMetaData metaData;
  private BizTableSchemaMapper mapper;
  private BizTableSchemaRegistrar registrar;

  @BeforeEach
  void setUp() throws SQLException {
    dataSource = mock(DataSource.class);
    connection = mock(Connection.class);
    metaData = mock(DatabaseMetaData.class);
    mapper = mock(BizTableSchemaMapper.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metaData);
    registrar = new BizTableSchemaRegistrar(
        dataSource, mapper, new ObjectMapper(), mock(PlatformTransactionManager.class));
  }

  @Test
  @DisplayName("读取到表与列后先清空再逐表写入列定义 JSON")
  void shouldReplaceSnapshot_whenMetadataReadSucceeds() throws Exception {
    ResultSet tables = mock(ResultSet.class);
    when(tables.next()).thenReturn(true, true, false);
    when(tables.getString("TABLE_SCHEM")).thenReturn("biz");
    when(tables.getString("TABLE_NAME")).thenReturn("t_a", "t_b");
    when(metaData.getTables(any(), eq("biz"), eq("%"), any())).thenReturn(tables);
    ResultSet colsA = mock(ResultSet.class);
    when(colsA.next()).thenReturn(true, false);
    when(colsA.getString("COLUMN_NAME")).thenReturn("id");
    when(colsA.getString("TYPE_NAME")).thenReturn("int8");
    when(colsA.getString("IS_NULLABLE")).thenReturn("NO");
    ResultSet colsB = mock(ResultSet.class);
    when(colsB.next()).thenReturn(false);
    when(metaData.getColumns(any(), eq("biz"), eq("t_a"), eq("%"))).thenReturn(colsA);
    when(metaData.getColumns(any(), eq("biz"), eq("t_b"), eq("%"))).thenReturn(colsB);

    registrar.registerOnStartup();

    InOrder order = inOrder(mapper);
    order.verify(mapper).deleteAll();
    order
        .verify(mapper)
        .upsertEntry("biz", "t_a", "[{\"name\":\"id\",\"type\":\"int8\",\"nullable\":false}]");
    order.verify(mapper).upsertEntry("biz", "t_b", "[]");
  }

  @Test
  @DisplayName("读取元数据失败时不刷新已有快照")
  void shouldKeepSnapshot_whenMetadataReadFails() throws Exception {
    when(metaData.getTables(any(), anyString(), anyString(), any()))
        .thenThrow(new SQLException("boom"));

    registrar.registerOnStartup();

    verify(mapper, never()).deleteAll();
    verify(mapper, never()).upsertEntry(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("写入登记表失败时吞掉异常不向外抛出")
  void shouldSwallowFailure_whenWritingRegistryFails() throws Exception {
    ResultSet tables = mock(ResultSet.class);
    when(tables.next()).thenReturn(false);
    when(metaData.getTables(any(), eq("biz"), eq("%"), any())).thenReturn(tables);
    doThrow(new IllegalStateException("db down")).when(mapper).deleteAll();

    registrar.registerOnStartup();

    verify(mapper).deleteAll();
  }
}
