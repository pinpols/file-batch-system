# Staging 脚本

本目录用于 staging 部署后的只读检查，不替代发布系统，也不执行删除、强制回滚或驱逐 Pod。

## 入口

- `verify-worker-rolling-upgrade.sh`：检查 Worker Deployment 的 rollout、Pod 状态和最近事件。

## 约定

- 需要本机已安装并已认证的 `kubectl`，通过 `KUBE_CONTEXT` 和 `KUBE_NAMESPACE` 指定目标环境。
- 脚本默认只读；升级动作由受控发布流程执行。
- 检查失败必须保留命令输出，供 staging 验收记录引用。
