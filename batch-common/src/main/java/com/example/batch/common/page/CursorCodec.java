package com.example.batch.common.page;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

/**
 * Cursor token 编解码工具 — ADR-031。
 *
 * <p>形态:{@code base64(JSON({"id":123, "createdAt":"2026-..."}))}。
 *
 * <ul>
 *   <li>不透明:用户不解读,FE 拿到原样回传
 *   <li>版本兼容:JSON 字段加减不破坏旧 token(decode 缺字段 → null,业务侧处理)
 *   <li>失效降级:无效 base64 / 损坏 JSON → 返回 empty map,**不抛异常**(对调用方意味着 WHERE 谓词命中 0 行,自然返回空列表)
 * </ul>
 *
 * <p><b>安全</b>:cursor 是用户可控输入,SQL 必须保证 tenant_id / 权限谓词在 cursor 谓词之前,绝不能让 cursor 解码出的 id 影响租户隔离。
 */
public final class CursorCodec {

  private CursorCodec() {}

  /** 把排序键 map 编码成 base64(JSON) token。空 map 返回 null。 */
  public static String encode(Map<String, Object> sortKey) {
    if (sortKey == null || sortKey.isEmpty()) {
      return null;
    }
    String json = JsonUtils.toJson(sortKey);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 解码 token。任何失败(null / blank / 非 base64 / 非 JSON / 非对象)都返回 empty map。
   *
   * <p>调用方 mapper 用 OGNL 引用 {@code #{cursor.id}} 时,如果 key 不存在,MyBatis 会传 null;SQL 用 {@code where id
   * < null} 自然返回 0 行,达到安全降级。
   */
  public static Map<String, Object> decode(String token) {
    if (token == null || token.isBlank()) {
      return Collections.emptyMap();
    }
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(token);
      String json = new String(bytes, StandardCharsets.UTF_8);
      Map<?, ?> raw = JsonUtils.fromJson(json, Map.class);
      if (raw == null) return Collections.emptyMap();
      // 强转 Map<String, Object>;Jackson 反序列化 JSON 对象的 key 永远是 String
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) raw;
      return typed;
    } catch (RuntimeException ex) {
      // base64 / JSON 解码异常,以及任何 cursor 损坏情况统一安全降级
      SwallowedExceptionLogger.info(CursorCodec.class, "catch:decode-cursor", ex);
      return Collections.emptyMap();
    }
  }
}
