# 配置导入与前后端契约核对

## API-1 源码契约到真实调用

Controller/DTO/校验/权限→OpenAPI→生成类型→API客户端→页面/状态/下载模板逐段核对。生成类型通过不能证明Controller路径已同步，也不能证明表单枚举、数值上限或错误翻译正确；Cookie/CSRF与内部Key是不同认证边界，按现行协议而不是旧JWT例子实施。

## API-2 Preview不是Apply

分别核对单行CRUD、批量部分成功、严格Job Bundle与完整租户配置包的事务语义，不从其中一种推导另一种。测试invalid行、跨sheet/库中引用、预览后修改、租户切换和重复确认，验证失败不部分落库、旧预览不能误写新目标；具体绑定字段以当前契约为准。

Excel显示顺序不等于后端依赖写入顺序；数据sheet、辅助说明与环境治理对象区分，不能凭旧文件名或sheet数量恢复退役Pipeline写入口。按当前schema/模板生成器取数，不在技能冻结数量。

## API-3 配置同步与运行快照

对确认人展示哪些资源和环境字段会变、何时生效、是否只影响新实例；审批通过、发布完成、目标回执、缓存刷新和业务运行结果分别取证。不能只检查toast或返回200；外部结果未知沿原操作身份对账，不用新幂等键盲重试。

## 当前来源

- [console-api-protocol.md](../../../../docs/api/console-api-protocol.md)
- [tenant-config-package-excel-9plus2-design.md](../../../../docs/design/tenant-config-package-excel-9plus2-design.md)
- [first-tenant-config-quickstart.md](../../../../docs/runbook/first-tenant-config-quickstart.md)
- [config-governance.md](../../../../docs/runbook/config-governance.md)
- [README.md](../../../../scripts/codegen/README.md)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。
