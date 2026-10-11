package io.github.pinpols.batch.console.support.excel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PreviewSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 用于多副本 Console 部署的加密共享上传会话存储。 */
@Component
@RequiredArgsConstructor
public class RedisTenantConfigExcelStore implements TenantConfigPackageExcelImportStore {

  private static final String KEY_PREFIX = "batch:console:tenant-config-upload:";
  private static final Duration SESSION_TTL = Duration.ofMinutes(30);
  private static final Duration APPLY_TTL = Duration.ofHours(1);
  private static final TypeReference<PackageExcelSession> SESSION_TYPE = new TypeReference<>() {};

  private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>("""
      redis.call('HSET', KEYS[1], 'payload', ARGV[1], 'state', 'READY', 'preview_token', '')
      redis.call('PEXPIRE', KEYS[1], ARGV[2])
      return 1
      """, Long.class);
  private static final DefaultRedisScript<Long> ISSUE_PREVIEW =
      new DefaultRedisScript<>("""
      if redis.call('EXISTS', KEYS[1]) == 0
          or redis.call('HGET', KEYS[1], 'state') == 'APPLYING' then
        return 0
      end
      redis.call('HSET', KEYS[1], 'preview_token', ARGV[1])
      return 1
      """, Long.class);
  private static final DefaultRedisScript<String> ISSUE_PREVIEW_SNAPSHOT =
      new DefaultRedisScript<>("""
          if redis.call('EXISTS', KEYS[1]) == 0
              or redis.call('HGET', KEYS[1], 'state') == 'APPLYING' then
            return false
          end
          local payload = redis.call('HGET', KEYS[1], 'payload')
          redis.call('HSET', KEYS[1], 'preview_token', ARGV[1],
              'preview_config_revision', ARGV[2])
          return payload .. ':' .. ARGV[1] .. ':' .. ARGV[2]
          """, String.class);
  private static final DefaultRedisScript<Long> CLAIM_APPLY =
      new DefaultRedisScript<>("""
      if redis.call('EXISTS', KEYS[1]) == 0
          or redis.call('HGET', KEYS[1], 'state') ~= 'READY'
          or redis.call('HGET', KEYS[1], 'preview_token') ~= ARGV[1] then
        return 0
      end
      redis.call('HSET', KEYS[1], 'state', 'APPLYING', 'apply_owner', ARGV[2])
      redis.call('PEXPIRE', KEYS[1], ARGV[3])
      return 1
      """, Long.class);
  private static final DefaultRedisScript<Long> COMPLETE_APPLY =
      new DefaultRedisScript<>("""
      if redis.call('EXISTS', KEYS[1]) == 0
          or redis.call('HGET', KEYS[1], 'state') ~= 'APPLYING'
          or redis.call('HGET', KEYS[1], 'apply_owner') ~= ARGV[1] then
        return 0
      end
      return redis.call('DEL', KEYS[1])
      """, Long.class);
  private static final DefaultRedisScript<Long> RELEASE_APPLY =
      new DefaultRedisScript<>("""
      if redis.call('EXISTS', KEYS[1]) == 0
          or redis.call('HGET', KEYS[1], 'state') ~= 'APPLYING'
          or redis.call('HGET', KEYS[1], 'apply_owner') ~= ARGV[1] then
        return 0
      end
      redis.call('HSET', KEYS[1], 'state', 'READY')
      redis.call('HDEL', KEYS[1], 'apply_owner')
      redis.call('PEXPIRE', KEYS[1], ARGV[2])
      return 1
      """, Long.class);
  private static final DefaultRedisScript<Long> REPLACE_SESSION =
      new DefaultRedisScript<>("""
      if redis.call('EXISTS', KEYS[1]) == 0
          or redis.call('HGET', KEYS[1], 'state') ~= 'READY'
          or redis.call('HGET', KEYS[1], 'preview_token') ~= ARGV[1] then
        return 0
      end
      redis.call('HSET', KEYS[1], 'payload', ARGV[2], 'preview_token', ARGV[3],
          'preview_config_revision', ARGV[4])
      redis.call('PEXPIRE', KEYS[1], ARGV[5])
      return 1
      """, Long.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final BatchObjectCryptoService cryptoService;

  @Override
  public String save(PackageExcelSession session) {
    String token = UUID.randomUUID().toString().replace("-", "");
    redisTemplate.execute(
        SAVE, List.of(key(token)), encode(session), Long.toString(SESSION_TTL.toMillis()));
    return token;
  }

  @Override
  public PackageExcelSession get(String uploadToken) {
    String payload = redisTemplate.<String, String>opsForHash().get(key(uploadToken), "payload");
    return EmptyChecks.isNull(payload) ? null : decode(payload);
  }

  @Override
  public PreviewSnapshot issuePreviewSnapshot(String uploadToken, long configurationRevision) {
    String previewToken = UUID.randomUUID().toString().replace("-", "");
    String stored = redisTemplate.execute(
        ISSUE_PREVIEW_SNAPSHOT,
        List.of(key(uploadToken)),
        previewToken,
        Long.toString(configurationRevision));
    if (EmptyChecks.isNull(stored)) {
      return null;
    }
    int revisionSeparator = stored.lastIndexOf(':');
    int tokenSeparator =
        revisionSeparator < 0 ? -1 : stored.lastIndexOf(':', revisionSeparator - 1);
    if (tokenSeparator < 0) {
      throw new IllegalStateException("invalid config upload preview snapshot");
    }
    return new PreviewSnapshot(
        decode(stored.substring(0, tokenSeparator)),
        stored.substring(tokenSeparator + 1, revisionSeparator),
        Long.parseLong(stored.substring(revisionSeparator + 1)));
  }

  @Override
  public boolean isApplyInProgress(String uploadToken) {
    String state = redisTemplate.<String, String>opsForHash().get(key(uploadToken), "state");
    return "APPLYING".equals(state);
  }

  @Override
  public boolean claimForApply(String uploadToken, String previewToken, String applyOwnerToken) {
    if (EmptyChecks.isNull(previewToken) || EmptyChecks.isNull(applyOwnerToken)) {
      return false;
    }
    return Long.valueOf(1)
        .equals(redisTemplate.execute(
            CLAIM_APPLY,
            List.of(key(uploadToken)),
            previewToken,
            applyOwnerToken,
            Long.toString(APPLY_TTL.toMillis())));
  }

  @Override
  public long configurationRevisionForApply(String uploadToken, String applyOwnerToken) {
    String sessionKey = key(uploadToken);
    String state = redisTemplate.<String, String>opsForHash().get(sessionKey, "state");
    String owner = redisTemplate.<String, String>opsForHash().get(sessionKey, "apply_owner");
    String revision =
        redisTemplate.<String, String>opsForHash().get(sessionKey, "preview_config_revision");
    if (!"APPLYING".equals(state)
        || !applyOwnerToken.equals(owner)
        || EmptyChecks.isNull(revision)) {
      throw new IllegalStateException("config upload apply claim lost its preview revision");
    }
    return Long.parseLong(revision);
  }

  @Override
  public boolean replaceSession(
      String uploadToken,
      String expectedPreviewToken,
      PackageExcelSession session,
      String replacementPreviewToken,
      long configurationRevision) {
    if (EmptyChecks.isNull(expectedPreviewToken) || EmptyChecks.isNull(replacementPreviewToken)) {
      return false;
    }
    return Long.valueOf(1)
        .equals(redisTemplate.execute(
            REPLACE_SESSION,
            List.of(key(uploadToken)),
            expectedPreviewToken,
            encode(session),
            replacementPreviewToken,
            Long.toString(configurationRevision),
            Long.toString(SESSION_TTL.toMillis())));
  }

  @Override
  public void completeApply(String uploadToken, String applyOwnerToken) {
    redisTemplate.execute(COMPLETE_APPLY, List.of(key(uploadToken)), applyOwnerToken);
  }

  @Override
  public void releaseApply(String uploadToken, String applyOwnerToken) {
    redisTemplate.execute(
        RELEASE_APPLY,
        List.of(key(uploadToken)),
        applyOwnerToken,
        Long.toString(SESSION_TTL.toMillis()));
  }

  @Override
  public void remove(String uploadToken) {
    redisTemplate.delete(key(uploadToken));
  }

  private String encode(PackageExcelSession session) {
    try {
      byte[] json = objectMapper.writeValueAsBytes(session);
      byte[] ciphertext = cryptoService.encrypt(json, null);
      return Base64.getEncoder().encodeToString(ciphertext);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "failed to encode encrypted config upload session", exception);
    }
  }

  private PackageExcelSession decode(String payload) {
    try {
      byte[] ciphertext = Base64.getDecoder().decode(payload);
      if (!cryptoService.isEncryptedContent(ciphertext)) {
        throw new IllegalStateException("config upload session payload is not encrypted");
      }
      byte[] json = cryptoService.decrypt(ciphertext);
      return objectMapper.readValue(new String(json, StandardCharsets.UTF_8), SESSION_TYPE);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "failed to decode encrypted config upload session", exception);
    }
  }

  private static String key(String uploadToken) {
    return KEY_PREFIX + uploadToken;
  }
}
