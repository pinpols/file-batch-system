package com.example.batch.console.application;

import com.example.batch.console.web.query.ConfigChangeLogQueryRequest;
import com.example.batch.console.web.query.ConfigReleaseQueryRequest;
import com.example.batch.console.web.query.SecretVersionQueryRequest;
import com.example.batch.console.web.request.ConfigReleaseActionRequest;
import com.example.batch.console.web.request.ConfigReleaseUpsertRequest;
import com.example.batch.console.web.request.SecretVersionRotateRequest;
import com.example.batch.console.web.response.ConsoleConfigChangeLogResponse;
import com.example.batch.console.web.response.ConsoleConfigReleaseResponse;
import com.example.batch.console.web.response.ConsoleSecretVersionResponse;
import java.util.List;

public interface ConsoleConfigApplicationService {

    List<ConsoleConfigReleaseResponse> configReleases(ConfigReleaseQueryRequest request);

    Long createConfigRelease(ConfigReleaseUpsertRequest request);

    String publishConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

    String grayConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

    String rollbackConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

    List<ConsoleSecretVersionResponse> secretVersions(SecretVersionQueryRequest request);

    Long rotateSecretVersion(SecretVersionRotateRequest request);

    List<ConsoleConfigChangeLogResponse> configChangeLogs(ConfigChangeLogQueryRequest request);
}
