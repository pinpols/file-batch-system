package com.example.batch.console.application.config;

import com.example.batch.console.web.query.ConfigChangeLogQueryRequest;
import com.example.batch.console.web.query.ConfigReleaseQueryRequest;
import com.example.batch.console.web.query.SecretVersionQueryRequest;
import com.example.batch.console.web.request.config.ConfigReleaseActionRequest;
import com.example.batch.console.web.request.config.ConfigReleaseUpsertRequest;
import com.example.batch.console.web.request.ops.SecretVersionRotateRequest;
import com.example.batch.console.web.response.config.ConsoleConfigChangeLogResponse;
import com.example.batch.console.web.response.config.ConsoleConfigReleaseResponse;
import com.example.batch.console.web.response.ops.ConsoleSecretVersionResponse;
import java.util.List;
import java.util.Map;

public interface ConsoleConfigApplicationService {

  List<ConsoleConfigReleaseResponse> configReleases(ConfigReleaseQueryRequest request);

  Long createConfigRelease(ConfigReleaseUpsertRequest request);

  String publishConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

  String grayConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

  String rollbackConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

  List<ConsoleSecretVersionResponse> secretVersions(SecretVersionQueryRequest request);

  Long rotateSecretVersion(SecretVersionRotateRequest request);

  List<ConsoleConfigChangeLogResponse> configChangeLogs(ConfigChangeLogQueryRequest request);

  ConsoleConfigReleaseResponse configReleaseDetail(String tenantId, Long releaseId);

  ConsoleSecretVersionResponse secretVersionDetail(String tenantId, Long secretVersionId);

  Map<String, Object> diffConfigReleases(String tenantId, Long releaseIdA, Long releaseIdB);

  /** 返回引用了 channel/template/calendar/window/queue 的 job 和 workflow。 */
  Map<String, Object> configDependencies(String tenantId, String configType, String configCode);
}
