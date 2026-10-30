# 配置消费、导入与密钥生命周期

## CFG-2 从键到运行分支

沿声明/绑定/校验/读取/分支/外部客户端核实配置实际消费，getter、registry和CRUD存在都不能证明生效。区分服务端端点、Worker listener及批大小钳制；开关关闭不一定关闭端点。针对默认、覆盖、非法、重启/刷新前后验证实际行为。

配置事实源不只键名：核对应用/Compose/Helm/脚本/profile的端口含义、默认值、环境范围和生命周期。外部profile来源和shell加载方式需审阅，不能把任意`.env`当无执行语义数据。

## CFG-3 配置导入影响现有运行

区分preview、确认绑定、apply、审批、发布、分发目标回执与真正生效。检查预览后内容或DB revision变化的处理、原子性、缓存失效、多实例一致性和配置版本冻结；HTTP 200或目标receipt不是所有在途任务已切新配置的证据。

## CFG-4 密钥轮换与固定快照

按现行Dispatch生命周期追实例创建时固定secretRef/version、排队/重试读取及每次实际投递前撤销/有效期校验。轮换与撤销不同：不能把旧任务静默切到最新版本，也不能用旧快照绕过撤销。明文不进实例快照、消息和日志，运行态凭据在使用结束后清除。已发请求不能召回，结果未知先对账再恢复。

## 当前来源

- [config-governance.md](../../../../docs/runbook/config-governance.md)
- [config-key-access-governance.md](../../../../docs/runbook/config-key-access-governance.md)
- [config-governance-registry.yml](../../../../docs/runbook/config-governance-registry.yml)
- [feature-switch-registry.yml](../../../../docs/runbook/feature-switch-registry.yml)
- [dispatch-secret-version-runtime-lifecycle.md](../../../../docs/design/dispatch-secret-version-runtime-lifecycle.md)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。
