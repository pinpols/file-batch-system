package com.example.batch.common.mapper;

import com.example.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import java.util.List;

/**
 * {@code batch.business_tenant_placement}(platform 库)读 mapper:P2 tenant-routing 表驱动 placement。
 *
 * <p>跨持有 business 数据源的 worker 共用,绑定 platform SqlSessionFactory(同 {@link InformationSchemaMapper},
 * 各模块 {@code @MapperScan} 已含 {@code com.example.batch.common.mapper})。仅整表读供 {@code
 * DbTablePlacementResolver} 缓存;表写(迁片登记)由运维/租户维护流程负责,不在此 mapper。
 */
public interface BusinessTenantPlacementMapper {

  /** 全量读 placement 映射(表行数 = 显式登记的租户数,通常远小于总租户数)。 */
  List<BusinessTenantPlacementEntity> selectAll();
}
