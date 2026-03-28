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

/**
 * 控制台配置中心应用服务：配置发布单生命周期、密钥版本与变更审计查询。
 */
public interface ConsoleConfigApplicationService {

    /** 查询配置发布单列表。 */
    List<ConsoleConfigReleaseResponse> configReleases(ConfigReleaseQueryRequest request);

    /** 创建草稿配置发布单。 */
    Long createConfigRelease(ConfigReleaseUpsertRequest request);

    /** 发布配置（全量生效）。 */
    String publishConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

    /** 灰度发布配置。 */
    String grayConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

    /** 回滚配置发布。 */
    String rollbackConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

    /** 查询密钥版本列表。 */
    List<ConsoleSecretVersionResponse> secretVersions(SecretVersionQueryRequest request);

    /** 轮换密钥并生成新版本记录。 */
    Long rotateSecretVersion(SecretVersionRotateRequest request);

    /** 查询配置变更日志。 */
    List<ConsoleConfigChangeLogResponse> configChangeLogs(ConfigChangeLogQueryRequest request);
}
