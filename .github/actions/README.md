# 共享 GitHub Actions 索引

本目录只放复用步骤，独立触发入口在 [工作流总索引](../workflows/README.md)。新增、改名或删除 action.yml/action.yaml 时同步本页，脚本治理守卫检查跳转与用途。

| 共享动作 | 用途 | 主要输入与调用边界 |
|---|---|---|
| [detect-change-scope](detect-change-scope/action.yml) | 调用共享分类器，输出变更范围和测试分片要求 | event-name、base-sha、head-sha；无法获得可信 diff 时回退完整范围 |
| [setup-build-env](setup-build-env/action.yml) | 准备 JDK、Maven 缓存和可选 Testcontainers 镜像缓存 | java-version、compile-load-tests、cache-testcontainers；非测试 job 可关闭容器缓存 |
| [setup-python-guard-env](setup-python-guard-env/action.yml) | 安装 Python 治理检查共用依赖 | 无自定义输入；调用方负责 checkout 和 Python 运行时准备 |
