package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.SecretVersionEntity;
import com.example.batch.console.domain.query.SecretVersionQuery;
import java.util.List;
import java.util.Map;

public interface SecretVersionMapper {

  List<SecretVersionEntity> selectByQuery(SecretVersionQuery query);

  SecretVersionEntity selectById(Map<String, Object> params);

  Integer selectLatestVersionNo(Map<String, Object> params);

  int insertSecretVersion(Map<String, Object> params);

  int deactivateCurrentVersion(Map<String, Object> params);

  int updateSecretVersionStatus(Map<String, Object> params);
}
