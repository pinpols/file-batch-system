#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "${ROOT_DIR}/scripts/ci/license-allowlist.sh"

license_line_has_approved_alternative 'GNU General Public License, version 2 RocksDB JNI (org.rocksdb:rocksdbjni:10.1.3 - https://rocksdb.org)'
if license_line_has_approved_alternative 'GNU General Public License, version 2 RocksDB JNI extension (org.rocksdb:rocksdbjni-extra:1)'; then
  echo 'RocksDB JNI allowlist matched a non-exact component name' >&2
  exit 1
fi
if license_line_has_approved_alternative 'GNU General Public License, version 2 Jakarta Mail API (jakarta.mail:jakarta.mail-api:2.1.5)'; then
  echo 'Jakarta component unexpectedly matched the dual-license allowlist' >&2
  exit 1
fi
if license_line_has_approved_alternative 'Eclipse Public License - v 2.0 JUnit Jupiter (org.junit.jupiter:junit-jupiter:6.0.3)'; then
  echo 'JUnit unexpectedly matched the dual-license allowlist' >&2
  exit 1
fi

echo '✅ 通过 | code=LICENSE_ALLOWLIST_TEST | gate=许可证豁免精确匹配'
