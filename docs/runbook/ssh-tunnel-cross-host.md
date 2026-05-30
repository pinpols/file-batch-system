# 跨机 SSH 反向隧道:把 .13 的 BE/FE 暴露到 .15(2026-05-29 建立)

> 场景:开发机(Windows,LAN `192.168.1.13`)上跑着 BE 8 个容器 + FE nginx,要让另一台机器(macOS,LAN `192.168.1.15`)直接以 `localhost:2XXXX` 访问到这些服务(联调 / 外网网关跳板)。
>
> 反向隧道(`ssh -R`)从 .13 主动连出去到 .15,在 .15 上开 9 个 listen 端口,流量回灌到 .13 的服务。
> 不用改路由器、不用打洞,SSH 一条链路全过墙;.15 那侧不需要装 docker 或 BE 代码。

## 最终命令

**.13 (Windows host) 跑**:

```powershell
$sshArgs = @(
  '-N',
  '-o','BatchMode=yes',
  '-o','ServerAliveInterval=30',
  '-o','ServerAliveCountMax=3',
  '-o','ExitOnForwardFailure=yes',
  '-R','28080:localhost:8080',    # FE console
  '-R','28090:localhost:18090',   # console-api
  '-R','28091:localhost:18091',   # trigger
  '-R','28092:localhost:18092',   # orchestrator
  '-R','25432:localhost:15432',   # postgres-primary
  '-R','25433:localhost:15433',   # postgres-replica
  '-R','26379:localhost:16379',   # redis
  '-R','29092:localhost:19092',   # kafka
  '-R','29000:localhost:19000',   # minio
  'dengchao@192.168.1.15'
)
Start-Process ssh -ArgumentList $sshArgs -NoNewWindow -PassThru `
  -RedirectStandardError "$env:USERPROFILE\logs\ssh-tunnel-15.err.log"
```

**端口映射约定**:`.15` 上的对外口 = `.13` 上对应口 **首位加 1**(8080→28080,18090→28090,15432→25432)。

## 关键选项的意义

| 选项 | 作用 | 不加会怎样 |
|---|---|---|
| `-N` | 不执行远程命令,只挂隧道 | 同时打开远程 shell,关闭 shell 就断隧道 |
| `BatchMode=yes` | 禁交互(不弹密码 / passphrase 框) | 隧道断后 ssh 重连可能卡在终端 prompt |
| `ServerAliveInterval=30` | 每 30s 发心跳 | NAT / 路由器空闲超时会静默断,且 client 不知 |
| `ServerAliveCountMax=3` | 3 次心跳无应答(90s)就断重连 | 配合 keep-alive 决定多快放弃 |
| `ExitOnForwardFailure=yes` | 任一 `-R` 失败立即退出 | 半通状态,某些端口看似通其实没起 |

## 真踩过的坑(按命中顺序)

### 坑 1:PowerShell 生成 key 时 passphrase 被设成字面 `""`

```powershell
# 看起来对,实际错
ssh-keygen -t ed25519 -f $env:USERPROFILE\.ssh\id_ed25519 -N '""'
```

`'""'` 是 PowerShell 字面字符串 `""`(两个双引号字符),不是空字符串。ssh-keygen 把这两字符当 passphrase。

**症状**:`ssh -o BatchMode=yes ...` 报 `Permission denied`,server 端 `sshd -ddd` 显示:

```
Accepted key ED25519 SHA256:...
debug3: send packet: type 60                  ← server 让 client 签名
Postponed publickey for dengchao...
Connection reset by authenticating user      ← client 主动断
```

—— **server 已接受 key,但 client 用私钥签 challenge 时因 passphrase 不对失败,BatchMode 又不让交互,直接 reset。**

**正确生成**(用 .NET ArgumentList 绕开 PowerShell 引号解析):

```powershell
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = 'ssh-keygen'
$psi.UseShellExecute = $false
'-t','ed25519','-f',"$env:USERPROFILE\.ssh\id_ed25519",'-N','','-q' | ForEach-Object {
  $psi.ArgumentList.Add($_)
}
[System.Diagnostics.Process]::Start($psi).WaitForExit()
```

**事后修复已有 key 的 passphrase**(公钥不变,不用重传):

```powershell
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = 'ssh-keygen'
$psi.UseShellExecute = $false
'-p','-P','""','-N','','-f',"$env:USERPROFILE\.ssh\id_ed25519" |
  ForEach-Object { $psi.ArgumentList.Add($_) }
[System.Diagnostics.Process]::Start($psi).WaitForExit()
```

**验证**:

```powershell
ssh-keygen -y -P "" -f $env:USERPROFILE\.ssh\id_ed25519
# 输出公钥 + rc=0 → passphrase 真空
```

### 坑 2:macOS 上看 sshd 日志要用 unified log,不是 journalctl

```bash
# Linux 看法(macOS 没这俩)
sudo journalctl -u sshd -n 30
sudo tail -30 /var/log/auth.log
```

**macOS 正确姿势**(但很容易输出空):

```bash
sudo log show --predicate 'process == "sshd"' --last 10m --info
```

实际上最可靠的诊断:**临时跑前台 debug sshd**,精确到每一步:

```bash
sudo /usr/sbin/sshd -ddd -e -p 22222 2>/tmp/sshd-debug.log
# 客户端从另一台连一次:
#   ssh -p 22222 dengchao@192.168.1.15 'echo OK'
# 然后:
cat /tmp/sshd-debug.log
```

22 端口由 launchd 管,debug sshd 跑在 22222 不冲突。Ctrl+C 停 debug sshd 不影响生产 sshd。

### 坑 3:GatewayPorts 默认 no,反向口只绑 127.0.0.1

`ssh -R 28090:localhost:18090 dengchao@.15` 在 .15 上 listen,**默认只绑 .15 的 `127.0.0.1`** —— `.15` 自己 `curl localhost:28090` 通,LAN 其它机器 `curl 192.168.1.15:28090` **不通**。

**只让 .15 本机用**:默认配置即可,无需改。

**让 LAN 全可达**(.15 上):

```bash
echo 'GatewayPorts yes' | sudo tee -a /etc/ssh/sshd_config.d/gateway.conf
sudo launchctl kickstart -k system/com.openssh.sshd
```

然后 .13 上的 `-R` 改为 `-R '*:28090:localhost:18090'`(`*` 显式绑 0.0.0.0)。

## 诊断 cheatsheet

| 症状 | 优先排查 |
|---|---|
| `Permission denied (publickey,...)` 且 ssh -vvv 没有 `Server accepts key` | pubkey 没装上 / 装的不是当前用户 / authorized_keys mode 不是 600 |
| ssh -vvv 有 `Server accepts key`,之后 `Permission denied` | server 端 PAM/SACL/AllowGroups 拒(`sudo /usr/sbin/sshd -ddd -p 22222` 看精确原因)|
| ssh -vvv 有 `Server accepts key`,之后 `Connection reset by authenticating user [preauth]` | **client 私钥解锁失败**(passphrase 错 / 文件损坏);`ssh-keygen -y -P "" -f <key>` 测 |
| 隧道起来但 .15 上 `lsof -i :28090` 没 listen | `ExitOnForwardFailure=yes` 应该会立即退,检查 `ssh-tunnel-15.err.log` |
| 隧道半小时后自己断 | 加 `ServerAliveInterval=30 ServerAliveCountMax=3` |
| LAN 别的机器打 .15:28090 不通,.15 本机 curl 通 | `GatewayPorts=no`(见 坑 3)|

## 长期化(可选):Windows 计划任务托管

防开机断 / 进程被关:写一个 `start-ssh-tunnel-15.ps1` + `launch-ssh-tunnel-15.vbs`(同 `deploy-be` 那套模式),schtasks 注册 ONSTART + 每 5 min 健康检查(`Get-Process ssh -ErrorAction SilentlyContinue` 没有就重启)。

参考实现见 [`script-deploy.md`](script-deploy.md) §安装的 schtasks + vbs 模式。

## 杀隧道

```powershell
# 单进程
Get-Process ssh -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -like '*192.168.1.15*' } |
  Stop-Process

# 暴力(本机无其它 ssh 用法时)
Stop-Process -Name ssh -Force
```
