# 合规索引

第三方依赖许可证 + SBOM。供发布 / 审计 / 法务问询用。

## 文件清单

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [THIRD-PARTY-LICENSES.md](./THIRD-PARTY-LICENSES.md) | 全部第三方依赖的 license 清单（人读）| 发版前合规 review / 法务问询 |
| 02 | [sbom.json](./sbom.json) | CycloneDX 格式 Software Bill of Materials（机读，供 trivy / dependency-track 等扫描器消费）| CI 安全扫描 / 漏洞溯源 |
| 03 | [license-risk-assessment.md](./license-risk-assessment.md) | 许可证风险评估（391 依赖按 license 家族分类 + copyleft 传染风险 + 分发义务）| 对外分发 fat jar 前 / 法务问"这个项目能不能开源/商用" |

## 生成 / 更新流程

CI(`scripts/ci/check-license-compliance.sh`)每次依赖变化都会在 `target/bom.json` 重生 SBOM 并跑许可证门禁;入库的 `02 sbom.json` 与 `01 THIRD-PARTY-LICENSES.md` 是**定期人工同步的快照**:

```bash
# 1. 重新生成机器产物(license 聚合 + SBOM)
mvn -P compliance license:aggregate-add-third-party cyclonedx:makeAggregateBom -DskipTests

# 2. 同步入库快照
cp target/bom.json docs/compliance/sbom.json

# 3. 手工更新人读文档(新依赖/版本变化):
#    THIRD-PARTY-LICENSES.md / NOTICE / license-risk-assessment.md
```

具体执行步骤详见 [`../runbook/security-scan.md`](../runbook/security-scan.md)。
