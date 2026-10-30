---
name: release-deployment
description: 规划、审查或按明确授权执行平台联合版本发布、不可变Release Manifest、Compose部署晋级和回滚时使用。Git提交与PR用git-pr-workflow，SDK包发布用sdk-contract-governance。
---

# 应用发布与部署晋级

## 入口与范围

先确认用户需要发布计划、制品准备、部署、晋级、回滚还是结果复核，并固定目标环境和release set。查[版本发布流程](../../../docs/runbook/releasing.md)、[当前CD路线](../../../docs/runbook/compose-cd-roadmap.md)与[执行入口](../../../scripts/deploy/README.md)，按需读[发布核对方法](references/release-methods.md)。不从技能推导发布、远端工作流、tag、GitHub Release或生产写入授权。

平台联合版本、两仓源码SHA和逐服务镜像digest是不同概念；普通PR不自动升版本，不以某一仓库最新main替代已批准候选。需要Git/PR/tag操作时组合`git-pr-workflow`；数据库兼容与恢复组合`database-migration-safety`，当前本地开发启动仍用`developer-workflow`。

## 工作流程

1. 固定前后端提交、目标版本、完整manifest、环境配置入口、上一稳定清单和验收证据；记录dirty内容与实际制品的关系，不能把本地修改算进远端镜像。
2. 核对manifest结构与完整服务集合、SHA/digest、渲染后Compose或Helm引用。生成本地覆盖层、校验schema和制品可追溯性是准备工作，不等于环境已部署。
3. 查当前workflow的触发、参数、Environment和实际实现。当前生产晋级是否已接通须现场核对；设计章节、manifest里的PASSED字符串或脚本接受production参数都不证明生产门禁存在。
4. 只有已明确的部署动作才运行现行入口；部署机消费已构建digest，不重新Maven/npm构建。晋级沿用已验收的同一release set，不能分别拉取前后端“最新”镜像。
5. 将容器健康、源码身份/digest、真实业务smoke和前后端验收分别留证。计划过的测试、workflow排队、缺少凭据或未接主机都记为未验证。
6. 失败时保存首因和部署审计，核对回滚目标及兼容性后按现行恢复路径处理。镜像回滚不撤销Flyway、外部副作用或配置数据；回滚成功也不把原部署改记成功。恢复再次失败时停止循环，保留现场。

## 交付

给出releaseId、两仓SHA、逐服务digest、目标环境、实际动作/命令、验收层级、上一稳定版本、失败/回滚结果和未验证边界。用户只要求方案时交付具体可审阅清单，不触发环境变更；只要求某一动作时不自动发布另一环境或执行额外tag/PR操作。
