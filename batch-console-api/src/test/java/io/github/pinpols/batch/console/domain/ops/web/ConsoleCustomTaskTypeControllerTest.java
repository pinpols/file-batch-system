package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.ops.entity.CustomTaskTypeEntity;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleCustomTaskTypeQueryService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleCustomTaskTypeControllerTest {

  @Mock private ConsoleCustomTaskTypeQueryService queryService;
  @Mock private ConsoleResponseFactory responseFactory;

  @InjectMocks private ConsoleCustomTaskTypeController controller;

  private CustomTaskTypeEntity entity(String code) {
    CustomTaskTypeEntity e = new CustomTaskTypeEntity();
    e.setTaskTypeCode(code);
    e.setStatus("ACTIVE");
    return e;
  }

  @Test
  void listResolvesTenantAndQueriesActiveOnly() {
    CustomTaskTypeEntity e = entity("tenant_tx_import");
    when(queryService.listActive("tx")).thenReturn(List.of(e));
    when(responseFactory.success(List.of(e))).thenReturn(CommonResponse.success(List.of(e)));

    CommonResponse<List<CustomTaskTypeEntity>> resp = controller.list("tx");

    assertThat(resp.data())
        .hasSize(1)
        .extracting(CustomTaskTypeEntity::getTaskTypeCode)
        .contains("tenant_tx_import");
  }

  @Test
  void countReturnsMapperResult() {
    when(queryService.countActive("tx")).thenReturn(3L);
    when(responseFactory.success(3L)).thenReturn(CommonResponse.success(3L));

    assertThat(controller.count("tx").data()).isEqualTo(3L);
  }

  @Test
  void detailReturnsEntityWhenFound() {
    CustomTaskTypeEntity e = entity("tenant_tx_import");
    when(queryService.detail("tx", "tenant_tx_import")).thenReturn(e);
    when(responseFactory.success(e)).thenReturn(CommonResponse.success(e));

    assertThat(controller.detail("tenant_tx_import", "tx").data().getTaskTypeCode())
        .isEqualTo("tenant_tx_import");
  }

  @Test
  void detailThrowsNotFoundWhenMissing() {
    when(queryService.detail("tx", "missing")).thenThrow(BizException.class);

    assertThatThrownBy(() -> controller.detail("missing", "tx")).isInstanceOf(BizException.class);
  }
}
