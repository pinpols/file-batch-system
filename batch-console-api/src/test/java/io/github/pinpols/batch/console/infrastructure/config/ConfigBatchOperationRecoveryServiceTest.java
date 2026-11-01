package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationContext;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRecoveryResponse;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRecoveryResponse.Status;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRecoveryResponse.Target;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.ItemDetail;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.ItemStats;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.domain.config.param.ConfigBatchOperationInsertParam;
import io.github.pinpols.batch.console.domain.config.view.ConfigBatchOperationRow;
import io.github.pinpols.batch.console.mapper.ConfigBatchOperationMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("配置批量操作恢复：原始目标顺序与已确认回执")
class ConfigBatchOperationRecoveryServiceTest {

  private static final String AUDIT = "audit-tenant";
  private static final String OPERATION = "original-operation";
  private static final String IDENTITY = "original-identity";
  private static final String KIND = "JOB_BUNDLE_IMPORT";
  private static final String FIRST = """
      {"tenantId":"A","success":true,"resourceQueues":{
        "created":1,"updated":0,"skipped":0,"failed":0,"migrated":0,
        "details":[{"code":"queue","action":"CREATED","errorMessage":null}]}}
      """;
  private static final String SECOND = """
      {"tenantId":"B","success":false,"errorMessage":"配置导入失败"}
      """;
  private static final String THIRD = """
      {"tenantId":"A","success":true,"resourceQueues":{
        "created":0,"updated":1,"skipped":0,"failed":0,"migrated":0,
        "details":[{"code":"queue","action":"UPDATED","errorMessage":null}]}}
      """;
  private final ObjectMapper json = new ObjectMapper();

  @Mock
  private ConfigBatchOperationMapper mapper;

  @Test
  @DisplayName("登记保留重复目标、原始操作标识和响应元数据")
  void shouldRegisterOriginalTargetOrderAndMetadata() throws Exception {
    when(mapper.insertOperation(any())).thenReturn(1);
    JsonNode metadata = json.readTree("{\"summary\":{\"jobs\":1}}");
    ConfigBatchOperationRequest request = new ConfigBatchOperationRequest(
        AUDIT, OPERATION, IDENTITY, KIND, List.of("A", "B", "A"), metadata);

    ConfigBatchOperationContext context = service().begin(request);

    assertThat(context.auditTenantId()).isEqualTo(AUDIT);
    assertThat(context.operationId()).isEqualTo(OPERATION);
    ArgumentCaptor<ConfigBatchOperationInsertParam> captor =
        ArgumentCaptor.forClass(ConfigBatchOperationInsertParam.class);
    verify(mapper).insertOperation(captor.capture());
    ConfigBatchOperationInsertParam inserted = captor.getValue();
    assertThat(inserted.requestIdentityHash()).isEqualTo(IDENTITY);
    assertThat(inserted.operationKind()).isEqualTo(KIND);
    assertThat(json.readTree(inserted.targetsJson()))
        .isEqualTo(json.readTree("[\"A\",\"B\",\"A\"]"));
    assertThat(json.readTree(inserted.responseMetadataJson())).isEqualTo(metadata);
    verify(mapper).setTenantContext(AUDIT);
  }

  @Test
  @DisplayName("三个位置都有回执时完整返回原结果，重复租户不合并")
  void shouldReturnCompleteReceiptsInOriginalOrder() throws Exception {
    stubOperation();
    when(mapper.selectReceipt(AUDIT, OPERATION, 0, "A")).thenReturn(FIRST);
    when(mapper.selectReceipt(AUDIT, OPERATION, 1, "B")).thenReturn(SECOND);
    when(mapper.selectReceipt(AUDIT, OPERATION, 2, "A")).thenReturn(THIRD);

    ConfigBatchOperationRecoveryResponse response = service().find(AUDIT, IDENTITY, KIND);

    assertThat(response.operationId()).isEqualTo(OPERATION);
    assertThat(response.operationKind()).isEqualTo(KIND);
    assertThat(response.responseMetadata()).isEqualTo(Map.of("jobs", 1));
    assertThat(response.status()).isEqualTo(Status.COMPLETE);
    assertThat(response.targets())
        .containsExactly(
            new Target(0, "A", Status.COMPLETE, firstResult()),
            new Target(
                1,
                "B",
                Status.COMPLETE,
                TenantInitResult.builder()
                    .tenantId("B")
                    .success(false)
                    .errorMessage("配置导入失败")
                    .build()),
            new Target(2, "A", Status.COMPLETE, thirdResult()));
    InOrder calls = inOrder(mapper);
    calls.verify(mapper).setTenantContext(AUDIT);
    calls.verify(mapper).selectOperation(AUDIT, IDENTITY);
    calls.verify(mapper).setTenantContext("A");
    calls.verify(mapper).selectReceipt(AUDIT, OPERATION, 0, "A");
    calls.verify(mapper).setTenantContext("B");
    calls.verify(mapper).selectReceipt(AUDIT, OPERATION, 1, "B");
    calls.verify(mapper).setTenantContext("A");
    calls.verify(mapper).selectReceipt(AUDIT, OPERATION, 2, "A");
  }

  @Test
  @DisplayName("任一回执缺失时保留已确认结果并标记整体未知")
  void shouldKeepKnownResultsAndReportUnknownForMissingReceipt() throws Exception {
    stubOperation();
    when(mapper.selectReceipt(AUDIT, OPERATION, 0, "A")).thenReturn(FIRST);
    when(mapper.selectReceipt(AUDIT, OPERATION, 1, "B")).thenReturn(null);
    when(mapper.selectReceipt(AUDIT, OPERATION, 2, "A")).thenReturn(THIRD);

    ConfigBatchOperationRecoveryResponse response = service().find(AUDIT, IDENTITY, KIND);

    assertThat(response.status()).isEqualTo(Status.UNKNOWN);
    assertThat(response.targets())
        .containsExactly(
            new Target(0, "A", Status.COMPLETE, firstResult()),
            new Target(1, "B", Status.UNKNOWN, null),
            new Target(2, "A", Status.COMPLETE, thirdResult()));
    verify(mapper).selectReceipt(AUDIT, OPERATION, 1, "B");
  }

  @Test
  @DisplayName("身份登记冲突时停止，不读取目标回执或覆盖原操作")
  void shouldRejectDuplicateIdentityBeforeTargetWork() {
    when(mapper.insertOperation(any())).thenReturn(0);
    JsonNode metadata = json.createObjectNode();
    ConfigBatchOperationRequest request = new ConfigBatchOperationRequest(
        AUDIT, OPERATION, IDENTITY, KIND, List.of("A", "B", "A"), metadata);

    assertThatThrownBy(() -> service().begin(request))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.CONFLICT));

    verify(mapper).insertOperation(any());
    verify(mapper, never()).insertReceipt(any());
    verify(mapper, never()).selectReceipt(anyString(), anyString(), anyInt(), anyString());
  }

  @Test
  @DisplayName("目标回执的租户与原计划不一致时不作为确认结果")
  void shouldKeepMismatchedReceiptUnknown() throws Exception {
    stubOperation();
    when(mapper.selectReceipt(AUDIT, OPERATION, 0, "A")).thenReturn(FIRST);
    when(mapper.selectReceipt(AUDIT, OPERATION, 1, "B")).thenReturn(FIRST);
    when(mapper.selectReceipt(AUDIT, OPERATION, 2, "A")).thenReturn(THIRD);

    ConfigBatchOperationRecoveryResponse response = service().find(AUDIT, IDENTITY, KIND);

    assertThat(response.status()).isEqualTo(Status.UNKNOWN);
    assertThat(response.targets())
        .containsExactly(
            new Target(0, "A", Status.COMPLETE, firstResult()),
            new Target(1, "B", Status.UNKNOWN, null),
            new Target(2, "A", Status.COMPLETE, thirdResult()));
  }

  private TenantInitResult firstResult() {
    ItemDetail detail = new ItemDetail("queue", "CREATED", null);
    ItemStats queues = new ItemStats(1, 0, 0, 0, 0, List.of(detail));
    return TenantInitResult.builder()
        .tenantId("A")
        .success(true)
        .resourceQueues(queues)
        .build();
  }

  private TenantInitResult thirdResult() {
    ItemDetail detail = new ItemDetail("queue", "UPDATED", null);
    ItemStats queues = new ItemStats(0, 1, 0, 0, 0, List.of(detail));
    return TenantInitResult.builder()
        .tenantId("A")
        .success(true)
        .resourceQueues(queues)
        .build();
  }

  @Test
  @DisplayName("原操作不存在或类型不符时拒绝回查，且不读取目标回执")
  void shouldRejectMissingOrDifferentOperationBeforeLoadingReceipts() {
    ConfigBatchOperationRow row =
        new ConfigBatchOperationRow(AUDIT, OPERATION, IDENTITY, "TENANT_COPY", "[]", "{}");
    when(mapper.selectOperation(AUDIT, IDENTITY)).thenReturn(null, row);

    for (int attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(() -> service().find(AUDIT, IDENTITY, KIND))
          .isInstanceOfSatisfying(
              BizException.class,
              exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));
    }
    verify(mapper, never()).selectReceipt(anyString(), anyString(), anyInt(), anyString());
  }

  private ConfigBatchOperationRecoveryService service() {
    return new ConfigBatchOperationRecoveryService(mapper, json);
  }

  private void stubOperation() {
    ConfigBatchOperationRow row = new ConfigBatchOperationRow(
        AUDIT, OPERATION, IDENTITY, KIND, "[\"A\",\"B\",\"A\"]", "{\"jobs\":1}");
    when(mapper.selectOperation(AUDIT, IDENTITY)).thenReturn(row);
  }
}
