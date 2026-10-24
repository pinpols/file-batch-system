#!/usr/bin/env bash
# 用途：提供依赖许可证白名单的公共判定函数。
# 用法：由调用脚本 source scripts/ci/license-allowlist.sh 后使用公共变量或函数。

license_line_has_approved_alternative() {
  [[ "$1" =~ (^|[[:space:]])RocksDB[[:space:]]JNI[[:space:]]\( ]]
}
