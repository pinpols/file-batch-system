#!/usr/bin/env sh
# 用途：校验共享 Secret 与 Alertmanager 模板后启动通知编排进程。
# 用法：由观测容器入口调用；共享令牌挂载到 /run/secrets/alertmanager-bearer-token。
set -eu

template=/etc/alertmanager/alertmanager-template.yml
token_file=/run/secrets/alertmanager-bearer-token

if [ ! -r "$template" ]; then
  echo "Alertmanager template is not readable" >&2
  exit 1
fi
if [ ! -r "$token_file" ] || [ ! -s "$token_file" ]; then
  echo "Alertmanager shared bearer token file is missing or empty" >&2
  exit 1
fi

# 保留原有令牌字符集和最小长度约束，只校验内存值，不输出内容。
token="$(cat "$token_file")"
case "$token" in
  ''|*[!A-Za-z0-9_-]*)
    echo "Alertmanager shared bearer token has an invalid format" >&2
    exit 1
    ;;
esac
if [ "${#token}" -lt 32 ]; then
  echo "Alertmanager shared bearer token must contain at least 32 characters" >&2
  exit 1
fi
unset token

# 模板通过 credentials_file 读取挂载 Secret，不再将令牌复制到生成配置或命令行。
exec /bin/alertmanager --config.file="$template" "$@"
