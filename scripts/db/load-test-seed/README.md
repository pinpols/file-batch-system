# 压测种子数据

这里放压测前置的可重复种子 SQL。

## 作用

- 为 staging / prod-like 环境准备压测所需的基础作业定义和工作流定义
- 支持 `load-tests` 里的 Gatling Simulation 运行前置数据

## 文件

- [load-test-seed.sql](./load-test-seed.sql)

## 使用建议

1. 先完成本地或 staging 环境的基础依赖启动
2. 再执行压测种子 SQL
3. 最后运行对应的压测脚本

## 特点

- 幂等：使用 `ON CONFLICT DO NOTHING`
- 目标环境专用：默认只在压测环境执行一次，不要求每次压测前重复导入
- 与系统测试种子分离：系统测试使用 [../test-seed/README.md](../test-seed/README.md)

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [load-test-seed.sql](load-test-seed.sql) | 压测专用种子数据 |
