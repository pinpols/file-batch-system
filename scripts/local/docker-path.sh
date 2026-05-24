#!/usr/bin/env bash
# 供 start-all.sh / stop-all.sh source:在 PATH 中未找到 docker 时,补齐常见安装路径。
#
# Docker 在各平台的标准 / 常见路径(用作 PATH fallback 查找次序):
#   ── Linux ───────────────────────────────────────────────
#     /usr/bin/docker                              apt / dnf / yum / pacman 标准位置(docker-ce / docker.io)
#     /usr/local/bin/docker                        manual install(get.docker.com 脚本默认装这里)
#     /snap/bin/docker                             Ubuntu snap 安装
#     /run/current-system/sw/bin/docker            NixOS
#     ${HOME}/.nix-profile/bin/docker              Nix 单用户
#   ── macOS ───────────────────────────────────────────────
#     /usr/local/bin/docker                        Intel Mac Homebrew / Docker Desktop symlink
#     /opt/homebrew/bin/docker                     Apple Silicon Homebrew
#     /Applications/Docker.app/Contents/Resources/bin/docker   Docker Desktop 自带 (macOS)
#     ${HOME}/.rd/bin/docker                       Rancher Desktop
#     ${HOME}/.docker/bin/docker                   Docker CLI plugin 路径
#   ── Windows(WSL2 走 Linux 路径;Git Bash 例外) ──────────
#     /c/Program Files/Docker/Docker/resources/bin/docker      Docker Desktop on Windows (Git Bash)
#     /mnt/c/Program Files/Docker/Docker/resources/bin/docker  WSL2 访问 host docker.exe(不推荐,慢)
#
# 注:WSL2 推荐启用 Docker Desktop "Use WSL 2 based engine",linux 侧自然 PATH 就有 docker。

ensure_docker_on_path() {
  if command -v docker >/dev/null 2>&1; then
    return 0
  fi
  local d
  for d in \
    "/usr/bin" \
    "/usr/local/bin" \
    "/opt/homebrew/bin" \
    "/snap/bin" \
    "${HOME}/.rd/bin" \
    "${HOME}/.docker/bin" \
    "${HOME}/.nix-profile/bin" \
    "/run/current-system/sw/bin" \
    "/Applications/Docker.app/Contents/Resources/bin" \
    "/c/Program Files/Docker/Docker/resources/bin"; do
    if [[ -x "${d}/docker" ]]; then
      export PATH="${d}:${PATH}"
      return 0
    fi
  done
  echo "ERROR: 未找到 docker 命令。" >&2
  echo "  Linux:   apt install docker.io  /  dnf install docker-ce  /  curl -fsSL get.docker.com | sh" >&2
  echo "  macOS:   brew install --cask docker        (Docker Desktop)" >&2
  echo "  Windows: 装 Docker Desktop 并启用 WSL2 engine" >&2
  echo "  已搜过的目录:/usr/bin、/usr/local/bin、/opt/homebrew/bin、/snap/bin、~/.rd/bin、~/.docker/bin、~/.nix-profile/bin、/run/current-system/sw/bin、Docker.app、Windows Docker Desktop" >&2
  exit 1
}
