package com.example.batch.console.domain.ops.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.dto.SdkCatalog;
import com.example.batch.console.domain.ops.service.ConsoleSdkCatalogService;
import com.example.batch.console.service.ConsoleResponseFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SDK 开发者门户后端(SDK 运行时可见性 ②)。
 *
 * <p>只读目录:平台协议版本 + 各语言 SDK 元数据 + 跨语言关键常量 + 文档索引,供前端门户渲染。静态元数据、非租户维度 (对所有租户一致),无 tenant 参数。
 *
 * <p><b>边界</b>:本端点是后端 API,实际 console 前端门户页在独立的 batch-console 前端仓接入,不在本仓范围。
 */
@RestController
@RequestMapping("/api/console/sdk")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
public class ConsoleSdkCatalogController {

  private final ConsoleSdkCatalogService catalogService;
  private final ConsoleResponseFactory responseFactory;

  /** GET /api/console/sdk/catalog — SDK 开发者门户目录(协议版本 / 各语言 / 共享常量 / 文档)。 */
  @GetMapping("/catalog")
  public CommonResponse<SdkCatalog> catalog() {
    return responseFactory.success(catalogService.catalog());
  }
}
