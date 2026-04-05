package com.example.batch.console.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Aggregate-root placeholder for Spring Data JDBC query-only repositories.
 * Dashboard/meta SQL targets multiple tables; this type exists only to satisfy
 * {@link org.springframework.data.repository.Repository} typing.
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
