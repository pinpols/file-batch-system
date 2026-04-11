package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.entity.ResourceTagEntity;
import com.example.batch.console.service.ConsoleResourceTagService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 资源标签管理：对 JOB / WORKFLOW / FILE_CHANNEL / FILE_TEMPLATE 打标签、按标签检索。 */
@RestController
@Validated
@RequestMapping("/api/console/tags")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
public class ConsoleResourceTagController {

    private final ConsoleResourceTagService tagService;
    private final ConsoleResponseFactory responseFactory;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;

    /** 查询指定资源的所有标签。 */
    @GetMapping
    public CommonResponse<List<ResourceTagEntity>> listByResource(
            @RequestParam("tenantId") String tenantId,
            @RequestParam("resourceType") @NotBlank String resourceType,
            @RequestParam("resourceCode") @NotBlank String resourceCode) {
        return responseFactory.success(
                tagService.listByResource(tenantId, resourceType, resourceCode));
    }

    /** 按标签键（可选值）反查资源。 */
    @GetMapping("/search")
    public CommonResponse<List<ResourceTagEntity>> searchByTag(
            @RequestParam("tenantId") String tenantId,
            @RequestParam("tagKey") @NotBlank String tagKey,
            @RequestParam(value = "tagValue", required = false) String tagValue) {
        return responseFactory.success(tagService.listByTagKey(tenantId, tagKey, tagValue));
    }

    /** 列出租户下所有已使用的标签键。 */
    @GetMapping("/keys")
    public CommonResponse<List<String>> listKeys(@RequestParam("tenantId") String tenantId) {
        return responseFactory.success(tagService.listDistinctKeys(tenantId));
    }

    /** 打标签（已存在则覆盖 value）。 */
    @PostMapping
    public CommonResponse<Void> upsert(
            @RequestParam("tenantId") String tenantId,
            @Valid @RequestBody UpsertTagRequest request) {
        String operator = requestMetadataResolver.current().operatorId();
        tagService.upsert(
                tenantId,
                request.resourceType(),
                request.resourceCode(),
                request.tagKey(),
                request.tagValue(),
                operator);
        return responseFactory.success(null);
    }

    /** 删除单个标签。 */
    @DeleteMapping
    public CommonResponse<Void> delete(
            @RequestParam("tenantId") String tenantId,
            @RequestParam("resourceType") @NotBlank String resourceType,
            @RequestParam("resourceCode") @NotBlank String resourceCode,
            @RequestParam("tagKey") @NotBlank String tagKey) {
        tagService.delete(tenantId, resourceType, resourceCode, tagKey);
        return responseFactory.success(null);
    }

    /** 删除资源的全部标签。 */
    @DeleteMapping("/all")
    public CommonResponse<Void> deleteAll(
            @RequestParam("tenantId") String tenantId,
            @RequestParam("resourceType") @NotBlank String resourceType,
            @RequestParam("resourceCode") @NotBlank String resourceCode) {
        tagService.deleteAllByResource(tenantId, resourceType, resourceCode);
        return responseFactory.success(null);
    }

    record UpsertTagRequest(
            @NotBlank @Size(max = 32) String resourceType,
            @NotBlank @Size(max = 128) String resourceCode,
            @NotBlank @Size(max = 64) String tagKey,
            @Size(max = 256) String tagValue) {}
}
