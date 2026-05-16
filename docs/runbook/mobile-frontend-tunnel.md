# 移动端访问本地前端（cloudflared tunnel + nginx proxy）

> 场景：开发期需要在手机上预览本地 `batch-console` 前端 UI（含登录走 console-api）。本文档记录一套实际跑通的配置链，**仅供短期开发调试**，URL 是公网可达且无鉴权。

## 架构

```
手机浏览器
   │ https://<random>.trycloudflare.com
   ▼
Cloudflare quick tunnel (公网)
   │
   ▼
本机 cloudflared 客户端
   │ http://localhost:5173
   ▼
本机 nginx (静态 dist + /api 反代)
   ├── / → /tmp/fe-share (npm run build 产物)
   └── /api/* → http://127.0.0.1:18080 (console-api)
                                 │
                                 ▼
                       本地 docker stack (postgres / kafka / redis / minio)
                       + 6 个 Java app (console-api / trigger / orchestrator / 4×worker)
```

## 前置

- macOS（已验证；Linux 同理但路径调整）
- 已装 `cloudflared`（`brew install cloudflared`）
- 已装 `nginx`（`brew install nginx`）
- 本地完整后端跑着（`bash scripts/local/start-all.sh`）
- `batch-console` 在 `~/Downloads/batch-console`（前端项目）

## 一次性配置

### Vite HMR 隧道兼容（dev 模式访问时需要）

如果走 `npm run dev` 模式（非生产 build），Vite HMR client 默认尝试 `wss://<host>:5173`，cloudflared 不放此端口，**手机端首屏白屏**。

修复：`vite.config.ts` 的 `server:` 段加 HMR 配置：

```ts
server: {
  port: 5173,
  allowedHosts: true,
  // R7 followup：通过 cloudflared 等 https 隧道访问时，page 在 :443，但 Vite HMR client
  // 默认 wss://<host>:5173 → 端口被隧道拒，浏览器报错后 Vue mount 卡住白屏。
  // 强制 client 走 443 + wss，让 HMR ws 走和页面同协议同端口。
  hmr: { clientPort: 443, protocol: 'wss' },
  // ... 其它 proxy 配置不变
}
```

**生产 build 模式（推荐）则不需要 HMR 配置**，跳过此步。

## 部署流程（每次开发会话）

### 1. 构建前端

```bash
cd ~/Downloads/batch-console
npm run build   # 产物在 ./dist，包含 sw.js (PWA Service Worker)
```

### 2. 拷贝 dist 到独立目录 + 替换 PWA SW 为 kill-switch

**关键**：手机 Safari 如果之前访问过同一 cloudflared 子域名，可能注册了 PWA Service Worker。新部署时旧 SW 会**劫持页面 → 白屏**。用 kill-switch SW 让所有旧 SW 自杀 + 清缓存 + 强刷。

```bash
rm -rf /tmp/fe-share
cp -R ~/Downloads/batch-console/dist /tmp/fe-share

cat > /tmp/fe-share/sw.js <<'JS'
// kill-switch SW：安装即注销 + 清所有缓存 + 强刷所有 client
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.map((k) => caches.delete(k)));
    await self.registration.unregister();
    const clients = await self.clients.matchAll({ type: 'window' });
    clients.forEach((c) => c.navigate(c.url));
  })());
});
JS
```

### 3. nginx 静态站 + /api 反代

```bash
cat > /tmp/festatic-proxy.conf <<'NGINX'
worker_processes 1;
events { worker_connections 256; }
http {
  include       /usr/local/etc/nginx/mime.types;
  default_type  application/octet-stream;
  sendfile      on;
  keepalive_timeout 65;
  access_log /tmp/nginx-fe.log;
  error_log  /tmp/nginx-fe.err warn;

  server {
    listen 5173;
    listen [::]:5173;
    root /tmp/fe-share;
    index index.html;

    # SPA history fallback
    location / {
      try_files $uri $uri/ /index.html;
    }

    # API 反代到 console-api（默认本地 18080）
    location /api/ {
      proxy_pass http://127.0.0.1:18080;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
      proxy_http_version 1.1;
      proxy_set_header Connection "";
      proxy_buffering off;
      proxy_read_timeout 1h;  # SSE 友好
    }
  }
}
NGINX

# 启动
nginx -c /tmp/festatic-proxy.conf
lsof -nP -iTCP:5173 -sTCP:LISTEN | head   # 验证监听
```

### 4. cloudflared quick tunnel

```bash
cloudflared tunnel --url http://localhost:5173 2>&1 | tee /tmp/cf-tunnel.log
# 等几秒，从日志拿 URL：
grep -oE "https://[a-z0-9-]+\.trycloudflare\.com" /tmp/cf-tunnel.log | head -1
```

复制 URL 在手机访问。

## 手机端常见踩坑

| 现象 | 原因 | 解决 |
|---|---|---|
| **白屏 + 网络正常** | 旧 Service Worker 劫持 | iOS Safari → 设置 → Safari → 高级 → 网站数据 → 找 `trycloudflare.com` → 删；或换 **无痕**模式 |
| **「无法建立连接」** | 手机网络屏 cloudflare（部分 ISP / VPN） | 切流量 / 不同 WiFi / 关 VPN |
| **POST 时 501 "Unsupported method"** | 用 python http.server（不支持 POST + 无 /api 代理） | 必须用 nginx 见步骤 3 |
| **登录 401 但 UI 显示** | console-api 没起 / 起在其它端口 | `lsof -nP -iTCP:18080 -sTCP:LISTEN`；起后端 `bash scripts/local/start-all.sh` |
| **dev 模式白屏（生产 build 不会）** | Vite HMR wss 端口被隧道挡 | vite.config 加 `hmr: { clientPort: 443, protocol: 'wss' }`，重启 vite dev |
| **首屏白 1-2 秒后正常** | kill-switch SW 在卸载旧 SW，下拉刷新一次即可 | 正常行为 |

## 收尾（用完务必关）

quick tunnel **公网可达 + 无鉴权**，任何拿到 URL 的人都能访问你本地后端。**用完立即关**。

```bash
pkill -f "cloudflared tunnel"
nginx -c /tmp/festatic-proxy.conf -s stop
rm -rf /tmp/fe-share /tmp/festatic-proxy.conf* /tmp/cf-tunnel.log /tmp/nginx-fe.*
```

## 固定 URL（永久化）

quick tunnel 每次重启换随机子域名。要固定 URL 选其一：

### A. Cloudflare Named Tunnel（推荐，免费，需自己域名在 CF）

```bash
cloudflared tunnel login          # 浏览器授权
cloudflared tunnel create batch-console
cloudflared tunnel route dns batch-console fe.<your-domain>
cat > ~/.cloudflared/config.yml <<YAML
tunnel: batch-console
credentials-file: /Users/<you>/.cloudflared/<uuid>.json
ingress:
  - hostname: fe.<your-domain>
    service: http://localhost:5173
  - service: http_status:404
YAML
cloudflared tunnel run batch-console
```

之后永久 URL = `https://fe.<your-domain>`。

### B. Tailscale Funnel（免费，无需自己域名）

```bash
brew install tailscale && sudo tailscale up
tailscale funnel 5173
# 自动给你一个 https://<machine>.<tailnet>.ts.net URL
```

### C. ngrok 付费 reserved domain

`ngrok http --domain=foo.ngrok-free.app 5173`（需付费账号）。

## 安全注意

- quick tunnel URL 是**公开的**，谁拿到链接都能访问，**不要发到公开聊天 / Issue**
- nginx 没做任何鉴权，公网直达 `/api/*` → console-api。**console-api JWT auth 是唯一防线**，确保 `BATCH_SECURITY_BYPASS_MODE=false` 在 dev profile 也开着真鉴权（默认 dev 是 bypass=true，**这意味着 quick tunnel 时任何人都能调 /api/console/users 建账号**，慎用）
- 移动端调试完**立即** `pkill -f "cloudflared tunnel"`
- 不要用此方式 demo / 给客户看，正经演示用 staging 部署 + 鉴权 + IP allowlist

## 关联文档

- [前端项目 batch-console README](https://github.com/your-org/batch-console)
- [本地后端启停](local-development.md)
- [Cloudflare Tunnel 官方文档](https://developers.cloudflare.com/cloudflare-one/connections/connect-apps/)
- [Tailscale Funnel](https://tailscale.com/kb/1223/funnel)
