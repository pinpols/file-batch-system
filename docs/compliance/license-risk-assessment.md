# 许可证风险评估（License Risk Assessment）

> 评估时间：2026-04-26  
> 数据源：`docs/compliance/sbom.json`（CycloneDX 1.6，`mvn -P compliance cyclonedx:makeAggregateBom` 产出，266 个 runtime + transitive 组件）  
> 项目自身许可：**Apache License 2.0**（见 `LICENSE`，`Copyright 2026 Dengchao`）

本文档**只评估 license 兼容性 / 分发义务 / copyleft 传染风险**，不涉及业务运行时的服务条款（OpenAI ToS、云服务 EULA 等）。

---

## TL;DR

| 维度 | 结论 |
|---|---|
| 高危依赖（CDDL / 纯 GPL / 商用受限 / unknown） | **0 个** |
| copyleft 传染风险 | 无（所有 GPL 类依赖都带 Classpath Exception，所有 LGPL 类依赖都是动态链接 + 双许可） |
| 你自己写的代码 | Apache-2.0，对外分发只需保留 `LICENSE` + `NOTICE` + 上游 attribution |
| 当前合规缺口 | 仅 1 处低优先级：`NOTICE` 文件极简（指针式），对外分发 fat jar 时严格法务可能要求嵌入完整 attribution 文本 |
| 内部使用 / 不公开二进制分发 | **可直接放行** |

---

## 1. License 家族分布（按依赖数量）

来自 `sbom.json` 实跑统计（jq 查询，参见 §5.2）。

| License 家族 | 代表组件 | 风险等级 | 说明 |
|---|---|---|---|
| **Apache-2.0** | Spring 全家桶 / Flyway / MyBatis / MinIO / OkHttp / POI / Quartz / Netty / Jackson / Micrometer / OpenTelemetry / ShedLock | 🟢 无 | 完全兼容 Apache-2.0 项目自身 |
| **MIT** | Lombok / SLF4J / Mockito / 部分 transitive | 🟢 无 | 完全宽松 |
| **BSD-2-Clause** | PostgreSQL JDBC | 🟢 无 | 仅需保留 copyright |
| **BSD-3-Clause** | JSch (mwiede fork) / ANTLR ST4 | 🟢 无（admin 缺口） | 严格要求保留 3 条款原文，详见 §3.3 |
| **EDL 1.0** | Angus Activation Registries | 🟢 无 | Eclipse Distribution License = 改名版 BSD-3 |
| **EPL-2.0 + LGPL（双许可）** | Logback Classic / Core 1.5.32 | 🟢 无 | 双许可可选 EPL-2.0 路径；动态链接库使用无义务，详见 §2.1 |
| **EPL-2.0 + GPL-2 w/ Classpath Exception** | Angus Mail / Jakarta Annotations API / Jakarta Mail API | 🟢 无 | Classpath Exception **明确允许**链接到非 GPL 代码，无传染，详见 §2.2 |
| **LGPL-2.1 OR Apache-2.0（双许可）** | JSqlParser 4.5 | 🟢 无 | 选 Apache-2.0 路径即可，无需走 LGPL，详见 §2.3 |
| **CDDL / MPL / 纯 GPL（无 CPE）/ AGPL / CC-BY-NC** | — | — | **未发现**（grep 0 命中） |
| **Unknown / undeclared** | — | — | **未发现**（generated 报告无 `unknown`） |

---

## 2. 主动管理项（看似有风险，实际 0 义务）

### 2.1 Logback Classic / Core 1.5.32 — EPL-2.0 OR LGPL

**实际 license 文本**（来自 generated 报告）：  
`(Eclipse Public License - v 2.0) (GNU Lesser General Public License) Logback Classic Module`

**风险评估**：
- 你**直接使用** Logback（Spring Boot starter 拉进来，没改源码）→ 无任何义务
- LGPL 的核心义务是"用户能用自己改过的版本替换链接的 LGPL 库"。fat jar 里 Logback 是独立 jar entry（不是源码级合并），用户可以替换 → 行业普遍认为合规
- EPL-2.0 路径在双许可里可任选 → 直接走 EPL 路径就避开 LGPL 的"必须可替换"义务

**触发风险的姿势**：fork Logback 源码并修改后再分发 → 必须公开你的修改（EPL-2.0 §3.1）。**当前未发生。**

### 2.2 Angus Mail / Jakarta Annotations / Jakarta Mail — GPL-2 with Classpath Exception

**实际 license 文本**：  
`(EDL 1.0) (EPL 2.0) (GPL2 w/ CPE) Angus Mail Provider`

**风险评估**：
- "Classpath Exception" 是 Oracle 在 OpenJDK 时代为 OSS 生态写的专门豁免：**允许 GPL 类库被任何 license 的代码调用，不传染调用方**
- 这正是 Java 标准库（OpenJDK 自身）能被商业软件免费用的法律基础
- Spring Boot 生态依赖了大量 Jakarta EE API，全都是这个模式 → **无任何风险**

**触发风险的姿势**：fork 这些库的源码并删除 CPE 声明再分发 → 不可能误操作。

### 2.3 JSqlParser 4.5 — LGPL-2.1 OR Apache-2.0

**实际 license 文本**：  
`(GNU Library or Lesser General Public License (LGPL) V2.1) (The Apache Software License, Version 2.0) JSQLParser library`

**风险评估**：
- 双许可（OR），用户**可任选其一**。我们选 Apache-2.0 路径 → 无 LGPL 义务
- 在 `THIRD-PARTY-LICENSES.md` 内部记录 + 对外 NOTICE 时声明 "JSqlParser used under Apache-2.0" 即可

---

## 3. 当前合规缺口（低优先级，不阻断使用）

### 3.1 `NOTICE` 文件偏极简

**现状**：当前 `NOTICE` 只有 16 行，指向 `docs/compliance/THIRD-PARTY-LICENSES.md` 和 `sbom.json`。

**Apache-2.0 §4(d) 严格要求**：  
> 如果 Work 包含一个 NOTICE 文件，You 必须在 Derivative Works 中包含此 NOTICE 文件中的 attribution notices

**风险场景**：
- ✅ 内部使用 / 不对外分发二进制 → **0 风险**
- ⚠️ 对外分发 fat jar 给客户、公开发 release artifact → 严格的法务团队可能要求把上游（Spring/Flyway/POI/Apache POI 等大头）的 NOTICE 文本嵌入到本项目 NOTICE 里
- 行业实际：大量 OSS 项目（包括 Spring Boot 自身的某些子模块）也只放指针式 NOTICE，社区接受度高，但不代表严格合规

**修复**（5 分钟）：跑 `mvn -P compliance license:aggregate-add-third-party`，把生成的 `target/generated-sources/license/THIRD-PARTY.txt`（或基于 `sbom.json` 的精简版）内容追加到 `NOTICE` 文件，或在 `NOTICE` 里明确链接到 `THIRD-PARTY-LICENSES.md`。

### 3.2 BSD-3-Clause attribution 文字未原文嵌入

**涉及**：JSch (`com.github.mwiede:jsch:0.2.23`)、ANTLR ST4

**BSD-3-Clause §1-3 严格要求**：分发二进制时必须包含 copyright notice + 三条款条文原文 + disclaimer 全文。

**现状**：当前 `THIRD-PARTY-LICENSES.md` 只列了一行简介。`THIRD-PARTY-GENERATED.txt` 也只列名字，未嵌全文。

**修复**：把 BSD-3-Clause 全文（10 行）作为附录贴到 `THIRD-PARTY-LICENSES.md` 末尾或独立 `licenses/BSD-3-Clause.txt`。

### 3.3 EPL-2.0 / LGPL secondary license 文本

**涉及**：Logback Classic / Core 1.5.32

**EPL-2.0 §3.1 要求**：分发 EPL-licensed material 时必须把 EPL-2.0 全文随附。

**修复**：同 §3.2，把 EPL-2.0 全文（~70 行）附为 `licenses/EPL-2.0.txt`。

---

## 4. 红线：以下姿势绝对不要做

| 操作 | 后果 |
|---|---|
| fork Logback / Angus Mail 源码并修改后分发 | 触发 EPL/GPL copyleft，必须公开修改 |
| 引入 license 是 **AGPL-3.0** 的依赖（如 MongoDB Java driver 老版、某些 Grafana 模块、ElementUI 商业版） | 整个**网络服务**都要开源 |
| 引入 license 是 **CC-BY-NC** 或 **JSON License** ("good, not evil") 的依赖 | 商用受限 / 法律措辞模糊 |
| 引入 **未声明 license** 的 jar | license 状态不明，等同侵权风险 |
| 移除 / 修改上游依赖的 `META-INF/LICENSE` `META-INF/NOTICE` 文件 | 违反几乎所有 OSS license 的 attribution 条款 |

---

## 5. 验证 / 重生成报告

### 5.1 完整刷新（每次升级依赖后跑）

```bash
# 同时生成 SBOM (CycloneDX) + 第三方 license 聚合
mvn -P compliance cyclonedx:makeAggregateBom license:aggregate-add-third-party

# 输出：
#   target/bom.json + target/bom.xml          ← CycloneDX SBOM
#   docs/compliance/THIRD-PARTY-GENERATED.txt ← 252 个依赖的 license 清单
#   docs/compliance/sbom.json                 ← 拷贝后的 SBOM 副本
```

### 5.2 红线快速扫描（CI 友好，0 输出 = 安全）

```bash
# 任何输出都需要人工 review
grep -iE "unknown|^\s*\(GPL[^2 ]|AGPL|CDDL|MPL|CC-BY-NC|json license" \
  docs/compliance/THIRD-PARTY-GENERATED.txt | grep -v -iE "Apache|w/ CPE|Classpath Exception"
```

**今天（2026-04-26）实跑结果**：0 命中 ✅

### 5.3 SBOM 可视化 / 上传到 Dependency-Track

`docs/compliance/sbom.json` 是 CycloneDX 1.6 格式，可直接导入 Dependency-Track / Snyk / Black Duck 做漏洞 + license 持续监控。

---

## 6. 当本项目"对外分发"时必做的清单

> 仅在以下场景需要走完整流程：发布 binary release、给客户/合作方交付 fat jar、上 Maven Central、推 Docker Hub 公开镜像

- [ ] `LICENSE` 文件存在且是 Apache-2.0 全文（**当前 ✅**）
- [ ] `NOTICE` 文件包含项目自身 copyright + 上游 NOTICE 聚合（**当前 ⚠️ 仅指针式**，见 §3.1）
- [ ] `META-INF/LICENSE` 在 fat jar 内（Spring Boot Maven plugin 默认会处理）
- [ ] 重新跑 `mvn -P compliance ...` 把 SBOM 和第三方清单嵌进 release artifact 或文档目录
- [ ] 检查新依赖：`git diff HEAD~N -- '**/pom.xml'` 看有没有引入 §4 红线 license

---

## 7. 关键决策记录

| 决定 | 理由 |
|---|---|
| 项目自身选 Apache-2.0 | 与 Spring 生态完全兼容；提供专利保护条款（比 MIT 强）；行业接受度最高 |
| 不引入 AGPL 依赖 | 避免"网络服务必须开源"的传染 |
| 不引入纯 GPL（无 CPE） | 避免静态链接传染；Java 生态主流库要么 Apache 要么有 CPE |
| 双许可依赖（JSqlParser / Logback）选宽松路径 | 在内部 license 记录里明确 declare "used under Apache-2.0" |
| Logback **不 fork 修改** | 避免 EPL/LGPL 二选一的修改公开义务 |

---

## 8. 相关文件

- `LICENSE` — 项目自身 Apache-2.0 全文
- `NOTICE` — 项目自身 attribution（待按 §3.1 升级）
- `docs/compliance/THIRD-PARTY-LICENSES.md` — 人工维护的依赖 license 摘要表
- `docs/compliance/THIRD-PARTY-GENERATED.txt` — `mvn -P compliance` 自动生成的完整清单（252 项）
- `docs/compliance/sbom.json` — CycloneDX 1.6 SBOM
- `pom.xml` `<profile id="compliance">` — 生成器配置
