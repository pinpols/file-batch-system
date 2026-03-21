package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.FileChannelConfigRecord;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface FileChannelConfigRepository extends CrudRepository<FileChannelConfigRecord, Long> {

    List<FileChannelConfigRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);
}
