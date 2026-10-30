# SDK源码、包与真发布的验证边界

## SDK-4 产物安装验证

源码conformance与构建出的包能安装/加载是两道证据。按语言从当前版本源和包元数据构建，隔离目录安装tarball/wheel/实际可分发产物，核对exports/types、依赖、资源、module路径及最小真实API调用；仓内import成功可能由源码路径掩盖漏打包。

## SDK-5 发布事件与外部状态

读当前release workflow的事件、条件、Environment和各registry鉴权，区分package、smoke、dry-run、report型供应链和publish。历史“已配审批/未配secret”需现场核实；手动workflow是否发布由当前if判定，不凭dry-run参数名猜测。SDK语言版本策略不强行套平台前后端联合版本。

本技能只在用户明确要求时执行对应registry/tag/Release动作，默认校验不会发布。发布后按精确版本/完整性及下游安装取证，不能只看发布job启动。

## 当前来源

- [sdk-release.md](../../../../docs/runbook/sdk-release.md)
- [sdk-python-release.md](../../../../docs/runbook/sdk-python-release.md)
- [sdk-release-validation.yml](../../../../.github/workflows/sdk-release-validation.yml)
- [byo-conformance-contract.md](../../../../docs/sdk/byo-conformance-contract.md)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。
