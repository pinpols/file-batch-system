# 工作树、构建产物与本地生命周期

## DEV-1 区分源码、编译产物与运行包

记录目标worktree/branch/HEAD、index/working tree和当前服务运行JAR或镜像身份。编译成功不证明服务使用新包；只构建叶模块可能拿到本地仓库旧上游，诊断需核对reactor依赖闭包、实际classpath和资源路径。使用当前Wrapper及项目构建入口，按用户限制选择静态/编译/测试，不自动clean或重启。

## DEV-2 进程、端口与锁的拥有权

沿`process.sh`、`local-lifecycle-lock.sh`和当前PID记录检查PID、命令行/JAR绝对路径及工作树，避免按模块名、端口或宽泛pkill误杀另一工作树。锁失效处理先证实拥有者已退出，不能见到锁就删除。

生命周期脚本可能做业务库bootstrap、迁移或清理，启动不是纯构建。启停顺序、drain、SIGTERM等待及强制终止选项按入口实际行为；多个任务共享端口/DB/Kafka时串行或证明隔离。

## DEV-3 本地与目标发布分开

本地源码/JVM、开发镜像和不可变发布集合不同；正式晋级使用`release-deployment`，不要把本地Docker重建当生产发布。读取`.env`内容不等于shell安全，加载未知配置前核对可信来源，不输出密钥。

## 当前来源

- [local-development.md](../../../../docs/runbook/local-development.md)
- [README.md](../../../../scripts/local/README.md)
- [process.sh](../../../../scripts/lib/process.sh)
- [local-lifecycle-lock.sh](../../../../scripts/lib/local-lifecycle-lock.sh)
- [business-db-bootstrap.sh](../../../../scripts/lib/business-db-bootstrap.sh)
- [local-log-layout.md](../../../../docs/runbook/local-log-layout.md)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。
