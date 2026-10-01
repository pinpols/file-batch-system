# 合规索引

第三方依赖许可证 + SBOM。供发布 / 审计 / 法务问询用。

## 文件清单

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [THIRD-PARTY-LICENSES.md](./THIRD-PARTY-LICENSES.md) | 全部第三方依赖的 license 清单（人读）| 发版前合规 review / 法务问询 |
| 02 | [sbom.json](./sbom.json) | CycloneDX 格式 Software Bill of Materials（机读，供 trivy / dependency-track 等扫描器消费）| CI 安全扫描 / 漏洞溯源 |
| 03 | [license-risk-assessment.md](./license-risk-assessment.md) | 许可证风险评估（SBOM 组件按 license 家族分类 + copyleft 传染风险 + 分发义务）| 对外分发 fat jar 前 / 法务问"这个项目能不能开源/商用" |

## 生成 / 更新流程

CI(`scripts/ci/check-license-compliance.sh`)每次依赖变化都会在 `target/bom.json` 重生 SBOM 并跑许可证门禁。入库的 `02 sbom.json` 是发布与审计使用的受控快照，依赖或构建插件变化时必须在同一 PR 更新；`pr-gate` 与 `full-ci-gate` 会调用 `check-sbom-sync.sh` 做字节级比对，防止动态产物与仓库快照漂移。`01 THIRD-PARTY-LICENSES.md` 仍需在引入新许可证家族或分发义务变化时人工复核。

启用仓库 `.githooks` 后，已暂存 POM 且不存在同文件未暂存改动时，pre-commit 会自动执行生成、许可证校验并暂存 `sbom.json`。CI 不自动提交生成结果，只验证 PR 已包含一致快照。

许可证门禁阻断强/网络 copyleft、source-available 与未知许可证。许可证风险说明、误报证据和分发义务仍由人工复核，自动化不得自行新增豁免。

```bash
# 1. 重新生成机器产物(license 聚合 + SBOM)
mvn -P compliance license:aggregate-add-third-party cyclonedx:makeAggregateBom -DskipTests

# 2. 同步入库快照
cp target/bom.json docs/compliance/sbom.json

# 3. 验证入库 SBOM 与当前依赖一致
bash scripts/ci/check-sbom-sync.sh

# 4. 人工复核人读文档（新许可证家族或分发义务变化）:
#    THIRD-PARTY-LICENSES.md / NOTICE / license-risk-assessment.md
```

具体执行步骤详见 [`../runbook/security-scan.md`](../runbook/security-scan.md)。
