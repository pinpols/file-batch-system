package com.example.batch.console.service;

import com.example.batch.console.domain.entity.ConfigChangeLogEntity;
import com.example.batch.console.domain.entity.ConfigReleaseEntity;
import com.example.batch.console.domain.entity.SecretVersionEntity;
import com.example.batch.console.domain.query.ConfigChangeLogQueryRequest;
import com.example.batch.console.domain.query.ConfigReleaseQueryRequest;
import com.example.batch.console.domain.query.SecretVersionQueryRequest;
import com.example.batch.console.domain.request.ConfigReleaseActionRequest;
import com.example.batch.console.domain.request.ConfigReleaseUpsertRequest;
import com.example.batch.console.domain.request.SecretVersionRotateRequest;
import java.util.List;

public interface ConsoleConfigApplicationService {

    List<ConfigReleaseEntity> configReleases(ConfigReleaseQueryRequest request);

    Long createConfigRelease(ConfigReleaseUpsertRequest request);

    String publishConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

    String grayConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

    String rollbackConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

    List<SecretVersionEntity> secretVersions(SecretVersionQueryRequest request);

    Long rotateSecretVersion(SecretVersionRotateRequest request);

    List<ConfigChangeLogEntity> configChangeLogs(ConfigChangeLogQueryRequest request);
}
