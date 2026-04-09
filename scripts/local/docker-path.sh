#!/usr/bin/env bash
# 供 start-all.sh / stop-all.sh source：在 PATH 中未找到 docker 时，补齐 Docker Desktop 常见安装路径。

ensure_docker_on_path() {
  if command -v docker >/dev/null 2>&1; then
    return 0
  fi
  local d
  for d in \
    "/usr/local/bin" \
    "/opt/homebrew/bin" \
    "${HOME}/.docker/bin" \
    "/Applications/Docker.app/Contents/Resources/bin"; do
    if [[ -x "${d}/docker" ]]; then
      export PATH="${d}:${PATH}"
      return 0
    fi
  done
  echo "ERROR: 未找到 docker 命令。" >&2
  echo "  请安装 Docker Desktop 并启动（macOS 可执行: open -a Docker），或把 docker 所在目录加入 PATH。" >&2
  echo "  常见路径: /usr/local/bin、/opt/homebrew/bin、~/.docker/bin、Docker.app/Contents/Resources/bin" >&2
  exit 1
}
