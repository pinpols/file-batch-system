# 合规索引

第三方依赖许可证 + SBOM。供发布 / 审计 / 法务问询用。

## 文件清单

| # | 文件 | 作用 | 何时看 |
|---|---|---|---|
| 01 | [THIRD-PARTY-LICENSES.md](./THIRD-PARTY-LICENSES.md) | 全部第三方依赖的 license 清单（人读）| 发版前合规 review / 法务问询 |
| 02 | [sbom.json](./sbom.json) | CycloneDX 格式 Software Bill of Materials（机读，供 trivy / dependency-track 等扫描器消费）| CI 安全扫描 / 漏洞溯源 |
| 03 | [license-risk-assessment.md](./license-risk-assessment.md) | 许可证风险评估（266 依赖按 license 家族分类 + copyleft 传染风险 + 分发义务）| 对外分发 fat jar 前 / 法务问"这个项目能不能开源/商用" |

## 生成 / 更新流程

```
mvn 依赖变化  →  CI 自动重生 02 sbom.json
                      ↓
              license-maven-plugin 跑出 01 THIRD-PARTY-LICENSES.md
```

具体执行步骤详见 [`../runbook/security-scan.md`](../runbook/security-scan.md)。
