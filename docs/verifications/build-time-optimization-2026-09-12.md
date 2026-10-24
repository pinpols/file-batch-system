# Java 应用构建耗时分析与优化验收

日期：2026-09-12  
范围：Maven reactor、应用 JAR、本地 Docker Compose、CI 应用镜像构建

## 1. 原因结论

Atomic 镜像构建耗时长不是 Atomic 源码自身编译慢，主要由构建边界过宽造成：

1. 单个服务镜像也执行除 `batch-e2e-tests` 外的全 reactor `package`，会编译 Console、Trigger、Orchestrator、全部 Worker 和 Java SDK。
2. `-DskipTests` 只跳过测试执行，不跳过约 937 个测试源码的编译。
3. 各模块测试依赖 `batch-common` 的 tests classifier，生产打包无法直接改用 `maven.test.skip=true`。
4. 多镜像构建依赖 Docker 隐式复用同一 builder，没有显式的 Buildx Bake DAG 和 CI 远程缓存。
5. 构建上下文包含与 Java 应用镜像无关的仓库内容，扩大上传和缓存失效范围。

## 2. 已实施方案

### 2.1 双构建路径

- `BUILD_MODE=module`：只执行 `-pl :${MODULE} -am package`，用于单服务开发和重建。
- `BUILD_MODE=all`：整套 8 个应用镜像共享一次全 reactor package，避免每个镜像重复编译。
- `scripts/docker/build-apps.sh` 在只指定一个应用服务时自动使用 `module`，多服务或全量构建使用 `all`。

### 2.2 生产构建不编译测试源码

- 新增 `batch-test-support`，承载跨模块测试基类、Testcontainers 基础设施和架构测试规则。
- 各业务模块通过 test scope 引用它，不再依赖 `batch-common:tests`。
- 生产 JAR/镜像使用 `maven.test.skip=true`；单元、集成、E2E 和静态门禁仍保留测试源码编译或真实执行。
- `batch-test-support` 不进入任何应用运行时 classpath，也不复制进最终镜像。

### 2.3 缓存与镜像输出

- POM 和源码分层，Maven 本地仓库使用 BuildKit cache mount。
- 新增 `docker-bake.hcl`，8 个镜像共享 builder DAG。
- 新增 `docker-bake.ci.hcl` 和可复用 GitHub Actions workflow，使用 GHA 远程 BuildKit cache。
- builder 只向后续阶段传递当前模块的 executable JAR，不再收集全部应用 JAR。
- `.dockerignore` 排除文档、Helm、报告、非 Java SDK、测试输出和本地工具目录。

## 3. 本机验收结果

测试环境为当前本机 Docker Desktop，镜像使用 JDK 21 构建和运行。

| 场景 | 结果 |
|---|---:|
| Atomic 单模块 Docker 构建（依赖缓存已存在、构建层首次生成） | 22.6s |
| Atomic 单模块 Docker 构建（完整暖缓存复跑） | 2.5s |
| 全 reactor Docker Maven package | 27.9s |
| 全路径 Atomic 镜像完整构建 | 39.0s |
| 本地 8 个应用 JAR 增量构建 | 11.1s |
| Docker build context | 约 0.7MB |
| Atomic 最终镜像 | 约 293MB |

构建日志确认所有生产 package 均显示 `Not compiling test sources`。Atomic 单模块路径只包含根、Common、Test Support、Worker 聚合、Worker Core、Atomic 六个 reactor 项目。

## 4. 正确性验证

- 全 reactor `test-compile`：通过。
- `batch-test-support` 真实测试：45 个通过，0 失败，覆盖 PostgreSQL、MinIO、RLS、分片路由和 ShedLock。
- 全 reactor `PMD + Spotless + test-compile`：通过。
- Docker `module` 与 `all` 两条路径：均构建成功。
- 最终 Atomic 镜像：`/app/app.jar` 有效、用户为 UID 10001、无 `batch-test-support` 运行时产物。
- Hadolint、Actionlint、构建脚本语法、文档引用和应用治理检查：通过。

## 5. 使用约束

1. 本地单服务构建优先执行 `./scripts/docker/build-apps.sh worker-atomic`，脚本自动选择模块闭包。
2. 整套镜像使用 `docker buildx bake`，不要并行启动 8 次互不关联的 Maven package。
3. `maven.test.skip=true` 只允许用于生产 JAR/镜像打包，不得用于 CI 单元、集成或 E2E 门禁。
4. CI 通过 `docker-bake.ci.hcl` 使用远程缓存；本地构建不依赖 GitHub Actions cache。
5. 修改根模块列表时，必须同步 Dockerfile 的 POM 预复制清单和 Bake 目标清单。

## 6. 剩余非阻塞项

- `dependency:go-offline` 仍会解析 reactor 中声明的测试依赖，但不会编译测试源码；收益继续受 Maven 仓库冷启动速度影响。
- Console 仍是最大单模块，当前约 870 个生产源码文件；进一步提速需要拆分其编译边界，属于架构改造，不应混入镜像构建优化。
- 最终镜像体积主要来自 JRE 与应用依赖；可另行评估 jlink/CDS，但这不会改善 Java 源码编译时间，且需要独立启动兼容性验证。
