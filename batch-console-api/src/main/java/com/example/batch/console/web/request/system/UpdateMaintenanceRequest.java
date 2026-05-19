package com.example.batch.console.web.request.system;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.Data;

/**
 * Admin 热更新维护状态请求体。
 *
 * <p>所有字段可选,缺省含义:enabled/readOnly 不传当 false;message/etaAt 不传当 null;affectedServices 不传当空 list。
 * 调用方应**整体替换**当前状态:想关维护就传 {@code {enabled:false}},想留消息就同时传 message。
 */
@Data
public class UpdateMaintenanceRequest {

  /** 维护总开关。 */
  private boolean enabled;

  /** 只读模式:true=GET 通过/写拒,false 且 enabled=true → 整站拒。 */
  private boolean readOnly;

  /** 用户可见提示语(<= 2048)。 */
  @Size(max = 2048)
  private String message;

  /** 预计恢复时间(ISO-8601)。 */
  private Instant etaAt;

  /** 受影响子系统 code 列表(<= 50 项,每项 <= 64 字符)。 */
  @Size(max = 50)
  private List<@Size(max = 64) String> affectedServices;
}
