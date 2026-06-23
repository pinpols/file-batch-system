# JVM 调优 与 性能 / 堆栈分析

生产 JDK 21 + Spring Boot 4 环境的 JVM 参数推荐 + 排障工具链 + 应急 SOP。本文档分 3 段:

- **§1 参数**:统一基础 + 按模块分档,直接抄进 Helm values
- **§2 工具**:profiling / 排障 toolkit + 装方式
- **§3 SOP**:5 类生产症状的应急取证流程

> 配套 `helm/values-prod.yaml` 的 `javaOpts` / 各模块 `javaOpts` override(模板见 §1 末尾)。

---

## §1 JVM 参数

### 1.1 统一基础(所有模块都加)

```
# === Container + 内存边界 ===
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=70.0          # 留 30% 给 native(Netty / Kafka / MinIO 大头)
-XX:InitialRAMPercentage=70.0      # heap 不动态扩,防 page fault 抖动
-XX:MaxDirectMemorySize=512m       # Netty / Kafka direct buffer 上限,不设会无界,超过 cgroup
-XX:NativeMemoryTracking=summary   # 出 OOM 用 jcmd NMT summary 看 off-heap
-XX:MaxMetaspaceSize=256m          # 防元空间无限增

# === OOM 留证据(必须) ===
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/app/heap-dump-%p-%t.hprof   # %p=pid %t=ts,不覆盖
-XX:+ExitOnOutOfMemoryError                            # OOM 直接退出,让 K8s 重启,不留僵尸

# === GC log(永久留,出问题不依赖 metrics)===
-Xlog:gc*=info,gc+age=trace,safepoint=info:file=/var/log/app/gc-%t.log:time,uptime,level,tags:filecount=10,filesize=50M

# === JIT / String 优化 ===
-XX:+UseStringDeduplication        # G1 配 80%+ Spring app String 重复,省 5-15% heap
-Djava.security.egd=file:/dev/./urandom

# === 时区 / 编码硬约束(对齐 CLAUDE.md)===
-Duser.timezone=UTC
-Dfile.encoding=UTF-8
-Dsun.jnu.encoding=UTF-8

# === JFR 持续 in-flight(几乎零开销,出事故能 dump 最后 10 分钟)===
-XX:StartFlightRecording=name=continuous,settings=profile,maxsize=256m,maxage=10m,dumponexit=true,filename=/var/log/app/jfr-exit.jfr

# === JDK 21 native access ===
--enable-native-access=ALL-UNNAMED
```

### 1.2 按模块分档

| 模块 | heap | GC | direct mem | 备注 |
|---|---|---|---|---|
| `batch-trigger` | 256-512M | G1(默认) | 默认 512M | 轻量 I/O,默认够 |
| `batch-orchestrator` | 1-2G | **Generational ZGC** | 默认 512M | 状态机大量短命对象,需要 sub-ms pause |
| `batch-worker-{import,export,process,dispatch}` | 1-2G | G1 | **1G** | 大文件流,direct 要扩 |
| `batch-console-api` | 768M-1.5G | G1 | 默认 512M | HTTP + SSE,中等 |

**Orchestrator 用 ZGC 的判断依据**:状态机 transition 链路每次生成大量短命对象(event / record / DTO),G1 容易 promote 到 old gen 触发 200ms+ Stop-the-world。ZGenerational(JDK 21+)在 1-2G heap 上 pause < 1ms,代价是吞吐 -10% 但**调度延迟换比 10% CPU 值**。

**Worker direct 扩到 1G 的判断依据**:Worker 通过 MinIO SDK(Netty)上传/下载,导出可能并发 4-8 个 1-300MB 文件。Direct buffer 用 `okhttp` 的 pool 复用,峰值 ~600-800MB。

### 1.3 Helm values 模板

```yaml
# helm/values-prod.yaml 顶层默认
javaOpts: >-
  -XX:+UseContainerSupport
  -XX:MaxRAMPercentage=70.0
  -XX:InitialRAMPercentage=70.0
  -XX:MaxDirectMemorySize=512m
  -XX:NativeMemoryTracking=summary
  -XX:MaxMetaspaceSize=256m
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/var/log/app/heap-dump-%p-%t.hprof
  -XX:+ExitOnOutOfMemoryError
  -Xlog:gc*=info,gc+age=trace,safepoint=info:file=/var/log/app/gc-%t.log:time,uptime,level,tags:filecount=10,filesize=50M
  -XX:+UseStringDeduplication
  -Djava.security.egd=file:/dev/./urandom
  -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8
  -XX:StartFlightRecording=name=continuous,settings=profile,maxsize=256m,maxage=10m,dumponexit=true,filename=/var/log/app/jfr-exit.jfr
  --enable-native-access=ALL-UNNAMED
  -Dspring.profiles.active=prod

# 每模块 override 在 helm/values-prod.yaml 的 services.<name>.javaOptsExtra
services:
  orchestrator:
    javaOptsExtra: "-XX:+UseZGC -XX:+ZGenerational"
  workerImport:
    javaOptsExtra: "-XX:MaxDirectMemorySize=1g"
  workerExport:
    javaOptsExtra: "-XX:MaxDirectMemorySize=1g"
  workerProcess:
    javaOptsExtra: "-XX:MaxDirectMemorySize=1g"
  workerDispatch:
    javaOptsExtra: "-XX:MaxDirectMemorySize=1g"
```

> Helm template 拼接:`JAVA_OPTS = javaOpts + " " + services.<name>.javaOptsExtra`,见 §落地 PR 改动。

### 1.4 Spring Boot 4 / JDK 21 新机制

**Virtual threads(Project Loom)**:
- console-api 适合开(纯 HTTP / SSE,IO 阻塞型);在 `application-prod.yml` 加 `spring.threads.virtual.enabled: true`
- worker / orchestrator 谨慎:底下 Kafka client / JDBC 有 native pin,VT 可能反而退化

**AppCDS / 动态 CDS(JDK 21)**:启动快 30-50%
（AOT cache 为 JDK 24+ 特性,当前平台 JDK 21 不适用,保留备未来升级）
```bash
# Dockerfile 中第一次启动写 archive
-XX:ArchiveClassesAtExit=/var/cache/app/app.jsa
# 第二次启动读
-XX:SharedArchiveFile=/var/cache/app/app.jsa
```

落地:Dockerfile 多阶段构建,build 阶段跑一次 dummy main 生成 jsa,运行阶段挂 read。

---

## §2 工具链

### 2.1 必装(生产容器内 / sidecar)

| 工具 | 角色 | 装方式 |
|---|---|---|
| **JDK 自带**:`jcmd` / `jstack` / `jmap` / `jstat` | 标准诊断 | JDK 自带,容器内有 java 就有 |
| **async-profiler** | 火焰图(CPU / Alloc / Lock) | `wget` 二进制,生产容器或 sidecar 都行,~5MB |
| **JFR** + **JDK Mission Control** | 持续录制 + 离线分析 | JFR 已在 §1.1 启用;JMC 装本地解析 dump |
| **arthas** | 在线热诊断(trace / watch / monitor) | `wget arthas-boot.jar`,attach 即用 |

### 2.2 离线分析

| 工具 | 看什么 |
|---|---|
| **Eclipse MAT** | heap dump → dominator tree / leak suspect / OQL |
| **gceasy.io** | GC log → throughput / pause / promotion failure(免费 web 工具)|
| **JDK Mission Control (JMC)** | JFR file → GC / Lock / IO / Method profiling |
| **Grafana**(你已有) | Prometheus 长期趋势 + Tempo trace 链 |

### 2.3 工具用法速查

```bash
# === jcmd:JDK 标准多功能诊断(永远先试这个)===
jcmd <pid> help                                    # 列所有支持的命令
jcmd <pid> VM.native_memory summary                # off-heap 分布
jcmd <pid> VM.native_memory summary.diff           # vs 上次 baseline 的 diff
jcmd <pid> VM.native_memory baseline               # 打 baseline
jcmd <pid> GC.heap_info                            # heap 内存分区
jcmd <pid> GC.class_histogram                       # heap histogram:按类统计实例数/字节,定位内存大户(等价 jmap -histo)
jcmd <pid> Thread.print                            # 等价 jstack
jcmd <pid> JFR.start name=adhoc duration=120s filename=/tmp/adhoc.jfr  # ad-hoc 录制
jcmd <pid> JFR.dump name=continuous filename=/tmp/now.jfr              # 从 §1.1 持续录制里 dump

# === JFR Method Timing & Tracing 注意:JEP 520 为 JDK 25 特性,当前平台 JDK 21 不可用 ===
# JEP 520(jdk.MethodTiming / jdk.MethodTrace)是 JDK 25 新增,21 上没有,不要直接套用下方“需 JDK 25”示例。
# 21 上用常规 JFR 替代(jcmd JFR.start),配合 JMC 离线分析定位慢方法:
jcmd <pid> JFR.start name=adhoc settings=profile duration=120s filename=/tmp/adhoc.jfr  # 常规 JFR profile 录制(JDK 21 可用)
jcmd <pid> JFR.dump name=adhoc filename=/tmp/method-profile.jfr        # 用 JMC 打开看 Method Profiling 视图定位热点
#
# 以下为 JEP 520 method timing 示例,仅当平台升级到 JDK 25+ 才可用(当前 JDK 21 不可用):
jcmd <pid> help JFR.start                                              # 需 JDK 25:看本机支持的 filter 写法
jcmd <pid> JFR.start name=mt jdk.MethodTiming#filter=io.github.pinpols.batch.<Class>::<method>  # 需 JDK 25
jcmd <pid> JFR.dump name=mt filename=/tmp/method-timing.jfr            # 需 JDK 25:用 JMC 打开看每方法累计耗时/调用次数

# === async-profiler:CPU / Alloc / Lock 火焰图 ===
./profiler.sh -d 60 -f /tmp/cpu.html <pid>                  # 60s CPU 火焰图(HTML)
./profiler.sh -e alloc -d 60 -f /tmp/alloc.html <pid>       # 内存分配火焰图
./profiler.sh -e lock -d 60 -f /tmp/lock.html <pid>         # 锁竞争火焰图
./profiler.sh -e wall -d 60 -f /tmp/wall.html <pid>         # wall-clock(包含 sleep / IO 等待)

# === arthas:热诊断不重启 ===
java -jar arthas-boot.jar <pid>
> dashboard                                                  # 实时 CPU / 内存 / 线程
> thread -b                                                  # 找死锁
> trace io.github.pinpols.batch.Foo doBar                          # 实时看方法耗时分布
> watch io.github.pinpols.batch.Foo doBar "{params, returnObj, throwExp}" -x 3  # 看入参 / 返回
> monitor -c 5 io.github.pinpols.batch.Foo doBar                   # 每 5s 输出方法 QPS / 平均耗时

# === 三连击 thread dump(死锁 / 卡顿)===
for i in 1 2 3; do jstack <pid> > /tmp/td-$i.txt; sleep 30; done
# 三次对比,所有 dump 里 stack 没动 = 真卡(死锁 / 无限循环 / 阻塞 IO)

# === 一键打包诊断证据(发事故 issue 用)===
jcmd <pid> VM.system_properties > /tmp/sysprops.txt
jcmd <pid> VM.flags > /tmp/flags.txt
jcmd <pid> Thread.print > /tmp/threads.txt
jcmd <pid> GC.heap_info > /tmp/heap.txt
jcmd <pid> GC.class_histogram > /tmp/histo.txt
jcmd <pid> VM.native_memory summary > /tmp/nmt.txt
jcmd <pid> JFR.dump name=continuous filename=/tmp/jfr.jfr
tar czf /tmp/diag-$(date +%s).tar.gz /tmp/{sysprops,flags,threads,heap,histo,nmt}.txt /tmp/jfr.jfr /var/log/app/gc-*.log
```

---

## §3 应急 SOP

按症状映射,**先取证再分析**。所有命令默认在容器内 `kubectl exec` 执行;权限不够走 sidecar。

### 3.1 Pod CPU 100%

```
1. top -H -p <pid>                              # 看哪个 thread 高 CPU,记 native tid(nid)
2. printf '%x\n' <nid>                          # 转 hex
3. jstack <pid> | grep -A 30 <hex_nid>          # 看是哪个 Java 方法
4. async-profiler 60s CPU 火焰图               # ./profiler.sh -d 60 -f cpu.html <pid>
5. 拿火焰图找占比 > 20% 的方法栈
```

**常见根因**:hot path 写日志(`log.info` 在循环)/ GC(看 gc log 是否高频)/ JIT thrash(C2 反编译循环)/ 序列化器(Jackson 反射)。

### 3.2 Pod OOMKilled

```
1. heap dump 已自动生成在 /var/log/app/(§1.1 配置)
2. kubectl cp pod:/var/log/app/heap-dump-*.hprof ./   # 拉本地
3. jcmd <pid> VM.native_memory summary | grep -E "Total|Java Heap|Class|Code|Direct" > nmt.txt
4. dmesg | grep -i oom                                # 看 cgroup OOM kill 时间
5. MAT 打开 hprof:Leak Suspects → Dominator Tree
```

**判 heap OOM vs native OOM**:
- 看 `dmesg`:`Killed process X (java) total-vm:Y` Y 远大于 -Xmx → native 涨(NMT diff 找)
- heap dump 文件大小 ~ 接近 -Xmx → heap OOM(MAT 看 retained heap)

**常见 native OOM**:Netty direct(增 `MaxDirectMemorySize`)/ Code Cache(`-XX:ReservedCodeCacheSize=256m`)/ Metaspace(`-XX:MaxMetaspaceSize`,已配)/ Thread stack 过多(`-Xss`)

### 3.3 慢请求(P99 飙升)

```
1. Grafana → Tempo:找慢 trace,看 span 链(哪段慢,DB / RPC / 自身)
2. 锁定方法后:
   arthas attach → watch <class> <method> "{params, returnObj, #cost}" -x 3 -n 20
3. async-profiler wall-clock 模式 60s
   ./profiler.sh -e wall -d 60 -f wall.html <pid>
   (wall 含 IO 等待,比 CPU 模式更适合"慢"问题)
4. 看 DB 慢查询:postgres slow log + pg_stat_statements
```

**常见根因**:N+1 query / 锁等待(看 wall 火焰图的 `parkNanos` 栈)/ Connection pool 干涸(jcmd Thread.print 看大量 `WAITING (parking)` 在 HikariCP)/ GC pause

### 3.4 GC pause 长 / 抖动

```
1. /var/log/app/gc-*.log 上传到 gceasy.io 或本地 Mission Control
2. 看指标:
   - Throughput < 95% → GC 太频繁
   - Pause p99 > 200ms(G1)/ 50ms(ZGC)→ 异常
   - Promotion failure / Concurrent mode failure → old gen 撑不住
   - Humongous allocation → 大对象直接进 old(看分配栈用 async-profiler -e alloc)
3. heap dump → MAT 看 old gen 占比最大对象
```

**常见根因**:堆设小了 / String 重复(开 `UseStringDeduplication`,已加)/ 缓存无界增长(看 dominator)

### 3.5 线程池打满 / 死锁

```
1. 三连击 thread dump
   for i in 1 2 3; do jcmd <pid> Thread.print > td-$i.txt; sleep 30; done
2. 三个 dump 里 stack 完全没动的 thread = 真卡 / 死锁
3. 直接看 dump 里的 deadlock report(JDK 自动检测 monitor / synchronized 死锁)
4. 复杂的 ReentrantLock / Semaphore 死锁:async-profiler -e lock 火焰图
   ./profiler.sh -e lock -d 60 -f lock.html <pid>
```

**常见根因**:`ExecutorService` 没设拒绝策略 / 阻塞队列无界 / Hikari pool size 比 worker 并发小 / arthas `thread -b` 直接列死锁

---

## §4 落地检查清单

- [ ] §1.3 Helm values 模板写入 `helm/values-prod.yaml`,各模块加 `javaOptsExtra`
- [ ] `helm/batch-platform/templates/configmap.yaml` 拼接 `JAVA_OPTS = javaOpts + javaOptsExtra`
- [ ] `helm/batch-platform/templates/*-deployment.yaml` 给每个 service mount `/var/log/app` PVC 或 emptyDir(否则 heap dump / GC log / JFR 丢)
- [ ] Dockerfile.app 加 `RUN mkdir -p /var/log/app /var/cache/app && apt-get install async-profiler`(可选 sidecar 代替)
- [ ] Spring Boot 4 console-api 开虚拟线程:`spring.threads.virtual.enabled: true`
- [ ] `docs/runbook/jvm-tuning-and-profiling.md` 即本文加进 `docs/runbook/README.md` 索引

---

## 关联文档

- [pg-session-tuning.md](./pg-session-tuning.md) — DB 侧超时
- [observability-stack.md](./observability-stack.md) — Prometheus / Tempo / Loki 部署
- [autoscaling-strategy.md](./autoscaling-strategy.md) — K8s HPA / VPA(memory request / limit 跟 JVM 参数配套)
- [ci.md](./ci.md) — CI 流水线(pr-gate / full-ci-gate)
