package com.example.batch.console.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC 仅查询仓储的聚合根占位类。
 * 仪表盘/元数据 SQL 涉及多张表；此类型仅用于满足
 * {@link org.springframework.data.repository.Repository} 的类型约束。
 */
@Table(schema = "batch", name = "job_instance")
public final class ConsoleJdbcQueryAnchor {

    @Id
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
