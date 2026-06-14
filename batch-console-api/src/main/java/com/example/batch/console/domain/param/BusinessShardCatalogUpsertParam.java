package com.example.batch.console.domain.param;

import lombok.Builder;

/** biz 分片目录登记入参(P2 tenant-routing)。只登记位置 + 状态,**不含账密**(secretRef 仅引用名)。 */
@Builder
public record BusinessShardCatalogUpsertParam(
    String placementKey,
    String host,
    int port,
    String dbName,
    String secretRef,
    Integer poolMaxSize,
    boolean enabled,
    String description,
    String operator) {}
