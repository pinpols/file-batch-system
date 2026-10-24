#!/usr/bin/env sh
# 用途：根据环境变量渲染 Alertmanager 配置。
# 用法：在仓库根目录执行 bash scripts/ops/render-alertmanager-config.sh；参数与前置环境见下方说明及所属 README。
set -eu

token=${BATCH_CONSOLE_ALERTMANAGER_BEARER_TOKEN:-}
case "$token" in
  ''|*[!A-Za-z0-9_-]*)
    echo "BATCH_CONSOLE_ALERTMANAGER_BEARER_TOKEN must use only letters, digits, '_' or '-'" >&2
    exit 1
    ;;
esac

if [ "${#token}" -lt 32 ]; then
  echo "BATCH_CONSOLE_ALERTMANAGER_BEARER_TOKEN must contain at least 32 characters" >&2
  exit 1
fi

template=/etc/alertmanager/alertmanager-template.yml
config=/tmp/alertmanager.yml
placeholder=REPLACE_WITH_AM_NOTIFY_BEARER_TOKEN

if ! grep -Fq "$placeholder" "$template"; then
  echo "Alertmanager template does not contain the expected token placeholder" >&2
  exit 1
fi

sed "s|$placeholder|$token|g" "$template" > "$config"
if grep -Fq "$placeholder" "$config"; then
  echo "Alertmanager config still contains the token placeholder" >&2
  exit 1
fi

exec /bin/alertmanager --config.file="$config" "$@"
