package io.github.pinpols.batch.console.domain.rbac.support;

/** 登录失败滑动窗口存储端口。 */
public interface LoginFailureStore {

  long recordFailure(String key, long now, long windowStart, String member, long ttlSeconds);

  long count(String key, long windowStart);

  void delete(String key);
}
