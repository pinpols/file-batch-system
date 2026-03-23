package com.example.batch.console.application;

import com.example.batch.console.domain.entity.ConfigChangeLogEntity;
import com.example.batch.console.domain.entity.ConfigReleaseEntity;
import com.example.batch.console.domain.entity.SecretVersionEntity;
import com.example.batch.console.web.query.ConfigChangeLogQueryRequest;
import com.example.batch.console.web.query.ConfigReleaseQueryRequest;
import com.example.batch.console.web.query.SecretVersionQueryRequest;
import com.example.batch.console.web.request.ConfigReleaseActionRequest;
import com.example.batch.console.web.request.ConfigReleaseUpsertRequest;
import com.example.batch.console.web.request.SecretVersionRotateRequest;
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
