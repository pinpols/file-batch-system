# 发布核对方法

## 制品身份与运行身份

从`release_manifest.py`实际schema和`deploy/release/release-manifest.example.json`核对字段；从构建metadata取得digest，不凭可移动tag推断。对照前后端源码SHA、实际构建ref及部署容器的镜像引用，前端另核对`/version.json`；一个组件正确不代表完整集合一致。

当前`compose-cd`是否仅手动main触发、前端SHA是否可达、默认是否只构建bundle、主机/Environment是否接入，每次读workflow与实际配置。目标生产拓扑与Phase 2 GitOps是设计范围，不自动成为当前生产能力。

## 子命令与副作用

先读`scripts/deploy/compose-release.sh`的`main`及调用链。`plan`校验并输出覆盖层；`verify`也会在release state目录创建本地覆盖文件，不能称作文件系统零写入；`deploy/rollback`会拉取/激活镜像并更新状态和审计。不能从子命令名判断只读性，更不能为查看帮助先执行未知脚本。

确认Compose project、external/local模式、前后端Compose文件、环境文件来源与state目录；不打印展开后的Secret。校验schema与渲染覆盖层不能替代目标主机健康、数据兼容或业务验收。

## 原部署失败与回滚失败分别记录

核对上一稳定manifest来源、当前stable/previous指向和失败快照，不按文件名猜当前生产版本。脚本内部回滚有何终止条件需读当前实现；外部重复执行可能再次改变状态，不盲目重跑。数据库升级、密钥撤销、外部通知和已执行任务分别制定恢复判断，不能用旧镜像掩盖结果未知。

## 当前来源

- [发布流程](../../../../docs/runbook/releasing.md)
- [Compose CD路线](../../../../docs/runbook/compose-cd-roadmap.md)
- [部署脚本](../../../../scripts/deploy/compose-release.sh)
- [Manifest工具](../../../../scripts/deploy/release_manifest.py)
- [Manifest样例](../../../../deploy/release/release-manifest.example.json)
- [工作流](../../../../.github/workflows/compose-cd.yml)
- [清单验证入口](../../../../scripts/ci/tests/test_release_manifest.py)
