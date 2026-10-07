package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.CustomTaskTypeResponse;
import io.github.pinpols.batch.console.domain.ops.entity.CustomTaskTypeEntity;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleCustomTaskTypeQueryService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("自定义任务类型接口:列表, 计数与详情透传查询服务结果, 记录缺失时抛出业务异常")
class ConsoleCustomTaskTypeControllerTest {

  @Mock
  private ConsoleCustomTaskTypeQueryService queryService;

  @Mock
  private ConsoleResponseFactory responseFactory;

  @InjectMocks
  private ConsoleCustomTaskTypeController controller;

  private CustomTaskTypeEntity entity(String code) {
    CustomTaskTypeEntity e = new CustomTaskTypeEntity();
    e.setTaskTypeCode(code);
    e.setStatus("ACTIVE");
    return e;
  }

  @Test
  @DisplayName("列表查询:先解析租户再查有效类型, 返回结果按任务类型编码回填")
  void shouldResolveTenantAndListActive_whenListingCustomTaskTypes() {
    CustomTaskTypeEntity e = entity("tenant_tx_import");
    when(queryService.listActive("tx")).thenReturn(List.of(e));
    CustomTaskTypeResponse expected = CustomTaskTypeResponse.from(e);
    when(responseFactory.success(List.of(expected)))
        .thenReturn(CommonResponse.success(List.of(expected)));

    CommonResponse<List<CustomTaskTypeResponse>> resp = controller.list("tx");

    assertThat(resp.data())
        .hasSize(1)
        .extracting(CustomTaskTypeResponse::taskTypeCode)
        .contains("tenant_tx_import");
  }

  @Test
  @DisplayName("计数查询:返回查询服务的统计结果")
  void shouldReturnMapperCount_whenCountingActiveTypes() {
    when(queryService.countActive("tx")).thenReturn(3L);
    when(responseFactory.success(3L)).thenReturn(CommonResponse.success(3L));

    assertThat(controller.count("tx").data()).isEqualTo(3L);
  }

  @Test
  @DisplayName("详情命中:按租户与类型返回记录, 任务类型编码与入参一致")
  void shouldReturnEntity_whenDetailFound() {
    CustomTaskTypeEntity e = entity("tenant_tx_import");
    when(queryService.detail("tx", "tenant_tx_import")).thenReturn(e);
    CustomTaskTypeResponse expected = CustomTaskTypeResponse.from(e);
    when(responseFactory.success(expected)).thenReturn(CommonResponse.success(expected));

    assertThat(controller.detail("tenant_tx_import", "tx").data().taskTypeCode())
        .isEqualTo("tenant_tx_import");
  }

  @Test
  @DisplayName("详情缺失:查询服务抛异常时原样向上抛出")
  void shouldPropagateBizException_whenDetailMissing() {
    when(queryService.detail("tx", "missing")).thenThrow(BizException.class);

    assertThatThrownBy(() -> controller.detail("missing", "tx")).isInstanceOf(BizException.class);
  }
}
