# 验收入口、结果与证据归属

## ACC-1 执行前确认依赖与副作用

静态、编译、unit/IT/E2E、SIM、strict与负载分别选入口。`run-full-regression`或BE验收可能启停服务、建库/写fixture和清理；阅读模式及子调用，不从“验收”推导共享环境写入授权。数据库/端口/消息命名空间共用时串行或证明隔离。

## ACC-2 消除假绿

进程退出码0、总入口成功与每项实际执行分别核对，读取run模式、SKIP/REPORT、生成报告和唯一run标识；轮询函数不能返回旧运行数据。声明全量前给场景分母和未完成项，不能拿历史36/36或脚本阶段名冒充本轮全覆盖。

## ACC-3 本次构建与业务闭环

核对实际JAR/镜像和dirty源码、真实依赖或替身、SQL持久化与外部副作用；HTTP、终态、日志和指标互相佐证。失败保留首因，修后只补相关检查，再按新风险扩大；用户要求仅静态或编译时遵守限制并如实留下业务验收缺口。

## 当前来源

- [be-acceptance.md](../../../../docs/runbook/be-acceptance.md)
- [release-gate.md](../../../../docs/testing/release-gate.md)
- [backend-sim-reliability.md](../../../../docs/testing/backend-sim-reliability.md)
- [run-full-regression.sh](../../../../scripts/ci/run-full-regression.sh)
- [be-acceptance.sh](../../../../scripts/local/be-acceptance.sh)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。
