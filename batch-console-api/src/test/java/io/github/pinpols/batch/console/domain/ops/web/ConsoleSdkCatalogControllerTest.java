package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.dto.SdkCatalog;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleSdkCatalogService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** SDK 运行时可见性 ②:验证 ConsoleSdkCatalogController 包装 service 输出为 CommonResponse。 */
@ExtendWith(MockitoExtension.class)
class ConsoleSdkCatalogControllerTest {

  @Mock private ConsoleSdkCatalogService catalogService;
  @Mock private ConsoleResponseFactory responseFactory;

  @InjectMocks private ConsoleSdkCatalogController controller;

  @Test
  void catalogReturnsWrappedServiceOutput() {
    // arrange
    SdkCatalog catalog = new ConsoleSdkCatalogService().catalog(); // 用真实结构当 fixture,避免重复手搓
    when(catalogService.catalog()).thenReturn(catalog);
    when(responseFactory.success(any(SdkCatalog.class)))
        .thenAnswer(inv -> CommonResponse.success(inv.getArgument(0)));

    // act
    CommonResponse<SdkCatalog> resp = controller.catalog();

    // assert
    assertThat(resp.data()).isSameAs(catalog);
    assertThat(resp.data().protocolVersion().supportedMajors()).containsExactly("v1", "v2");
    assertThat(resp.data().languages()).hasSize(5);
  }
}
