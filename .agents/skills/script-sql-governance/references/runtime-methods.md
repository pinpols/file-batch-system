# 脚本运行时、共享库与副作用核对

## SH-1 先核对子命令与共享调用

读参数解析、main分派、source/import和配套SQL，区分检查、渲染本地文件、准备数据、环境变更与破坏性清理；`inspect`、`heal`、`verify`或`--help`名称不证明无副作用。库文件不当入口执行，动态SQL路径与自动发现也算消费者。

## SH-2 环境与客户端fallback

复用`env-common.sh`、`runtime-defaults.sh`、`python-runtime.sh`和当前数据库/对象CLI封装。host/auto/docker支持范围由入口决定，不能全局强制Docker或默认能连远端；核对host映射端口、容器内端口和JVM实际监听键。

IPv6 authority使用既有解析/格式函数，传给libpq的host与URI authority区别处理。Python数据库fallback是最小psql兼容层，检查`-v`、引号、标识符、退出码、autocommit及元命令支持，不把迁移或交互psql脚本无条件交给fallback；缺客户端明确失败。

## SH-3 平台、失败与清理

按支持的Bash/OS核对GNU/BSD参数、数组、date/stat/readlink、超时和Python版本；项目局部venv与依赖入口优先，不为解决脚本依赖修改系统Python。语法/lint不证明目标平台运行成功。

读管道和后台任务真实退出码、trap覆盖及信号清理；清理只作用当前任务拥有的临时资源，不能吞掉业务失败。脚本与SQL分离后核对参数化、租户、目标库、事务和行数；验证主入口及fallback的失败传播，而不是只检查文件存在。

## 当前来源

- [README.md](../../../../scripts/README.md)
- [README.md](../../../../scripts/lib/README.md)
- [env-common.sh](../../../../scripts/lib/env-common.sh)
- [postgres-client.py](../../../../scripts/lib/postgres-client.py)
- [script-runtime-compatibility-matrix.md](../../../../docs/runbook/script-runtime-compatibility-matrix.md)
- [ipv6-runtime-compatibility.md](../../../../docs/runbook/ipv6-runtime-compatibility.md)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。
