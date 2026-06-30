# Dispatch Adapter Template

本文定义新增 dispatch adapter 时必须补齐的模板和验收点。目标是降低接入成本，但不放宽安全边界。

## 新增 adapter 的最小交付

| 项 | 要求 |
|---|---|
| 类型枚举 | 先在 `FileChannelType` 增加官方类型，再同步 `DispatchChannelTypePolicy` |
| 安全画像 | 在 `DispatchChannelSafetyProfile` 声明 timeout、credential、readback、knownGaps |
| 配置校验 | Console 写入前必须拒绝未知 `channel_type`，不能等 worker 运行期失败 |
| 运行实现 | 实现 `DispatchChannelAdapter`，`supports()` 只能接受官方类型 |
| 超时 | 所有网络和阻塞 IO 必须显式超时，不能依赖 JDK/SDK 默认值 |
| 凭据 | 只接受 credentialRef / secret backend，不允许明文落库 |
| 幂等 | externalRequestId / object key / manifest 必须能支持重投不重复成功 |
| 审计 | 成功、失败、重试、DLQ 必须能关联 tenantId/fileId/dispatchRecordId/traceId |
| 测试 | 单测覆盖 allowlist、失败重试、凭据缺失、超时、幂等重投 |

## 代码模板

```java
final class XxxDispatchChannelAdapter implements DispatchChannelAdapter {

  @Override
  public boolean supports(String channelType) {
    return FileChannelType.XXX.code().equals(channelType);
  }

  @Override
  public DispatchResult dispatch(DispatchCommand command) {
    // 1. 解析 channel config,校验 credentialRef 和 endpoint。
    // 2. 设置显式 connect/read/write timeout。
    // 3. 生成幂等 externalRequestId / object key / manifest。
    // 4. 执行发送。
    // 5. 返回可审计的状态和外部请求编号。
  }
}
```

## 安全属性检查表

- `TIMEOUT`: connect/read/write 或等价超时必须可配置。
- `CREDENTIAL_REF`: 凭据必须通过引用解析，不进入日志和响应。
- `SSRF_GUARD`: HTTP/API 类 adapter 必须做 host/IP allowlist 或 DNS guard。
- `PATH_SANDBOX`: LOCAL/NAS/SFTP 类 adapter 必须限制路径根目录，防止 path escape。
- `SIDECAR_MANIFEST`: 文件投递必须能产出 manifest/checksum，便于下游校验。
- `READBACK`: 若声明支持回读验证，必须实现实际读取和 checksum/size 校验。
- `SIZE_LIMIT`: 邮件、HTTP body、对象分片必须有大小上限。

## 明确不做

- 不开放任意第三方 adapter 通过 `supports()` 动态接入新类型。
- 不把 adapter 模板做成插件市场。
- 不把业务成功定义交给 adapter；adapter 只能证明交付动作成功或失败。
- 不在 shell 脚本里混写 SQL 和环境配置；SQL 放 `scripts/data/sql` 或 migration，环境变量走统一 env 管理。
