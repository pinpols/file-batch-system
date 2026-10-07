#!/usr/bin/env bash

license_line_has_approved_alternative() {
  [[ "$1" =~ (^|[[:space:]])RocksDB[[:space:]]JNI[[:space:]]\( ]]
}
