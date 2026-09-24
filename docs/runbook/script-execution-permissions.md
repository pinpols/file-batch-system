# 脚本执行权限恢复

本仓库通过 Git 文件模式保存脚本入口的执行权限。macOS/Linux 正常 clone 或 checkout 后，可直接执行标记为可执行的入口，例如：

```bash
./scripts/local/run-tests.sh --unit
```

如果解压源码包、复制文件或其他操作丢失了执行位，可从仓库 `HEAD` 一键恢复：

```bash
bash scripts/local/restore-script-execute-bits.sh
```

恢复脚本只对 Git 中模式为 `100755` 的已跟踪文件添加当前用户的执行位，范围限定为 `scripts/`、`load-tests/scripts/` 和 `.githooks/`。它不修改文件内容、不使用 `sudo`，也不改变 Git 中保存的文件模式。

不要对所有 `*.sh` 递归执行 `chmod +x`：共享库、环境文件和供其他脚本 `source` 的文件通常应保持非可执行。未标记为可执行、但需要临时运行的脚本仍可显式交给解释器：

```bash
bash scripts/ci/check-shell-scripts.sh
```

## 验证

检查 Git 保存的入口模式：

```bash
git ls-files -s 'scripts/**/*.sh' 'load-tests/scripts/**/*.sh' '.githooks/*'
```

`100755` 表示可直接执行；`100644` 表示普通文件，通常由 `bash path/to/script.sh` 调用或被其他脚本引用。恢复后可检查本地模式变化：

```bash
git diff --summary -- scripts load-tests/scripts .githooks
```

如果 `core.filemode=false`，本机 Git 可能不显示权限差异；这不影响当前文件系统的执行位。该脚本只恢复 `HEAD` 已记录的权限，不会猜测新建或未跟踪文件是否应设为可执行。
