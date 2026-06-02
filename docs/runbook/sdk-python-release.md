# batch-worker-sdk-python 发版 runbook

> 包名:`batch-worker-sdk-python`(PyPI distribution)
> import 路径:`batch_worker_sdk`(代码层不变)
> 模块目录:`batch-worker-sdk-python/`(与 Java `batch-worker-sdk*` 兄弟对齐)
> tag 前缀:`sdk-python-v`

## 1. 一次性:配 OIDC trusted publishing

工作流 `.github/workflows/sdk-python-publish.yml` 用 [PyPI trusted publishing](https://docs.pypi.org/trusted-publishers/),**不维护 `PYPI_TOKEN` secret**,GitHub OIDC 直接换 token。

### PyPI 项目页配置

1. 登录 `https://pypi.org/manage/project/batch-worker-sdk-python/settings/publishing/`
2. **Add a new pending publisher**:
   - Owner:`pinpols`
   - Repository name:`file-batch-system`
   - Workflow filename:`sdk-python-publish.yml`
   - Environment name:(留空)
3. TestPyPI 同样配一遍:`https://test.pypi.org/manage/project/batch-worker-sdk-python/settings/publishing/`

### 项目不存在时的首发

PyPI 不允许空项目配 trusted publisher。首发要先手 token 推一次:

```bash
cd batch-worker-sdk-python
pip install hatch twine
hatch build --clean
TWINE_USERNAME=__token__ TWINE_PASSWORD=$PYPI_API_TOKEN twine upload dist/*
```

成功后回 PyPI 配 trusted publisher。

## 2. 发版流程

### a. 改版本号

`batch-worker-sdk-python/src/batch_worker_sdk/_version.py`:

```python
__version__ = "0.4.0"
```

SemVer;Pre-Alpha 用 `0.x.y`,稳定后 `1.x.y`。

### b. PR merge 到 main

走标准 PR + auto-merge;**发版 PR 只动 `_version.py` 和 CHANGELOG**,不带其它特性。

### c. 打 tag 触发

```bash
git tag sdk-python-v0.4.0
git push origin sdk-python-v0.4.0
```

GitHub Actions 自动跑 `sdk-python-publish.yml`:
1. `hatch build --clean` 出 wheel + sdist
2. OIDC 换 PyPI token
3. `pypa/gh-action-pypi-publish` 推 PyPI
4. `skip-existing: true` 让重 tag 不炸

### d. 验证

```bash
pip install batch-worker-sdk-python==0.4.0
python -c "from batch_worker_sdk import BatchPlatformClient; print('ok')"
```

## 3. TestPyPI dry-run(正式发前演练)

Actions → `sdk-python-publish` → **Run workflow** → 勾 `dry-run: true`。

完成后:
```bash
pip install -i https://test.pypi.org/simple/ batch-worker-sdk-python
```

通过再走正式 tag。

## 4. 回滚

**PyPI 不允许真删版本**(防供应链)。回滚 = 发新 patch + yank 旧版:

1. `_version.py` → `0.4.1`
2. 修 + PR + merge
3. `git tag sdk-python-v0.4.1 && git push --tags`
4. PyPI 项目页手动 yank 0.4.0

## 5. 名字对照

| 维度 | 值 |
|---|---|
| 仓内目录 | `batch-worker-sdk-python/` |
| pyproject distribution name | `batch-worker-sdk-python` |
| PyPI 项目名 | `batch-worker-sdk-python` |
| `pip install` 名 | `batch-worker-sdk-python` |
| Python `import` | `batch_worker_sdk` |
| tag 前缀 | `sdk-python-v` |

PEP 标准:**distribution name 含 `-`,import name 用 `_`,两者可不同**。租户 `import batch_worker_sdk` 行为不变。
