# Console 登录防暴力破解 + 验证码 设计方案

> 状态:设计(2026-06-24)。本文聚焦 **console 登录端点的防暴力破解/撞库**,以及**可插拔验证码框架**。
> DDoS/接口限流是相邻但**独立**的线(见 §6),已有 ADR-019 + PR #708 + #694 在推进,本文不重复。
> 启动实施前以 `origin/main` 实际代码复核现状。

## 1. 目标与非目标

- **目标**:在不伤正常用户 UX、且**不引入"账号锁定 DoS"**的前提下,把 console 登录的防暴力破解/撞库从"仅 IP 限流"提升到"IP 限流 + 账号维度失败退避 + risk-based 验证码"。验证码做成**可插拔**(换厂商只换配置)。
- **非目标**:L3/L4 容量型 DDoS(在网关/CDN/WAF,见 §6);MFA(可后续作为独立增强)。

## 2. 威胁模型

| 威胁 | 说明 | 现有防御 | 缺口 |
|---|---|---|---|
| 单 IP 高速暴力破解 | 一个 IP 猛试密码 | ✅ IP 滑动窗口(10/分钟/IP,`ConsoleRateLimitFilter`) | — |
| 分布式低速撞库 | 代理池,每 IP 慢试,盯账号或撞一批账号 | ❌ 仅 IP 限流挡不住 | **账号维度失败退避** |
| 自动化撞库机器人 | 海量账号 + 分布式 IP + 脚本 | ❌ | **risk-based 验证码** |
| **账号锁定 DoS(lockout abuse)** | 攻击者故意输错锁死受害者 | — | **必须用"升级摩擦"而非"硬锁定"设计规避** |
| 用户枚举 | 探测某用户名是否存在 | ✅ `ConsoleLoginService` 统一返回 invalid credentials,不区分"用户不存在/密码错" | — |
| 凭据明文泄露 | 登录密码传输被截 | ✅ `console-login-encryption.md`(RSA 加密传输 `ConsoleLoginKeyPairService`) | — |

## 3. 现状(已有,勿重复造)

- IP 维度登录限流:`ConsoleRateLimitFilter`(`POST /api/console/auth/login`,默认 `loginIpLimitPerMinute=10`),底层 `SlidingWindowRateLimiter`(Redis ZSET + Lua,真滑动窗口,无边界突破)。
- 防用户枚举 + 登录加密 + 敏感/昂贵接口限流(后者 #708 已扩)。
- **没有**:账号维度失败计数/退避、验证码(任何形态)、lockout 机制。

## 4. 设计

四层纵深,**第 1 层已有**,本方案补 2/3/4。所有新机制走**总开关 + 默认 off**(opt-in,不破坏现状)。

### 4.1 账号 + IP 失败退避(补"分布式低速撞库")
- 失败计数:**仅密码错时**对 `login:fail:account:<user>` 和 `login:fail:ip:<ip>` 各 +1(Redis ZSET 滑动窗口,`LoginFailureTracker`;成功登录**立即清零**该账号计数,IP 计数保留——IP 是共享资源)。
- **渐进退避**:登录失败响应人为延迟 `200ms × 失败数`,**封顶 2s**。拖慢自动化吞吐,真实用户几乎无感(连错才递增)。
- 计数窗口:15 分钟滚动。
- **IP 维度取值** = `RemoteAddr`(与 `ConsoleJwtService` ipHash 同源,防 XFF 伪造)。**已知局限**:应用挂在共享反代/NAT 后时,所有用户共用反代 IP → IP 维度会聚合(阈值易被整体流量触发,真实客户端 IP 在 `X-Forwarded-For`)。此时**账号维度是主防线**;部署在共享出口后应调高/关掉 IP 维度阈值,或在反代层按真实 IP 限流。默认 off 让运维按拓扑调参后再开。

### 4.2 risk-based 验证码触发 —— **不硬锁,免疫 lockout DoS**
- **关键设计**:达阈值**不锁账号**,而是"**该登录要求验证码**"。
  - 默认阈值:**同账号或同 IP 失败 5 次 / 15 分钟 → 触发验证码**。
  - 受害者最多被要求做个验证码 → 攻击者**无法靠输错密码把别人锁出去**(OWASP:优先 throttling + CAPTCHA,慎用 account lockout)。
- 登录响应在触发时带 `captchaRequired=true`;前端弹验证码;用户过验证码得 token → 后端校验 token 通过**才校验密码**。
- **全程不写"account locked"状态**。如未来确需"锁定",只允许:短时自动解锁(分钟级) + 结合 IP/设备可疑信号 + 邮件通知 + 自助解锁——绝不纯靠失败次数永久锁。

### 4.3 通用可插拔验证码框架(`CaptchaVerifier` SPI)
登录逻辑只依赖抽象接口,换 provider 只换配置:
```java
public interface CaptchaVerifier { CaptchaResult verify(String token, String clientIp); }
// 实现各一类,@ConditionalOnProperty 按配置只装一个:
//   NoopCaptchaVerifier(默认,不验=通过) / SelfHostedSliderVerifier(自建滑块,不外联)
//   TencentCaptchaVerifier(天御) / AliyunCaptchaVerifier(阿里)
```
- 配置驱动:`batch.console.captcha.provider = none|selfhosted|tencent|aliyun` + 各 provider 密钥块。**切已接入 provider = 改一行配置,零代码改动。**
- 前端配置驱动:后端 `GET /api/console/captcha/config` 返回 `{provider, sitekey, enabled}`,前端动态加载对应 widget。
- 开闭:**切换已接入 provider 纯配置;接入全新 provider = 加一个实现类 + 前端加一段 widget 适配**。

### 4.4 总开关 + 默认值(全部可配)
```yaml
batch.console.login-protection:
  enabled: false                 # 总开关,默认关(opt-in,不破坏现状)
  fail-threshold: 5              # 同账号/IP 失败次数 → 触发验证码
  fail-window-minutes: 15
  backoff-step-millis: 200       # 退避 = 200ms × 失败数
  backoff-cap-millis: 2000       # 封顶 2s
batch.console.captcha:
  provider: none                 # none|selfhosted|tencent|aliyun
  # tencent/aliyun/selfhosted 各自配置块
```

## 5. 验证码选型决策(已拍板)

- **形态 = 图片拖动滑块**(UX 好、移动友好),但**必须带后端风控**——裸滑块(只校验缺口位置)能被轨迹模拟破解,强度在背后行为风控。
- **不用传统图形验证码**(扭曲字符):OCR/打码平台秒破、UX 差,已淘汰。
- **优先级**:
  1. **能外联第三方** → **腾讯天御 / 阿里云验证码**(国内可达好、无感+滑块、厂商风控)。国内服务**按量计费但有免费额度**;console 登录是 risk-based 低频(内部运维用户),量极小,**成本≈0(大概率免费额度内)**。
  2. **Cloudflare Turnstile** 完全免费但**国内可达性不稳**,国内部署不推荐。
  3. **合规/网络禁外联** → 自建滑块 + 后端轨迹/时序校验(弱一档,~1-2 周工时 + 需持续维护;定位是"抬门槛"非"真壁垒",配合 IP 限流 + 失败退避做纵深)。
- **通用框架让选型不阻塞**:v1 实装 `none`(默认,等效旁路) + `selfhosted`(保底,不外联、可本地跑可测);`tencent`/`aliyun` 是**冻结的"加一个实现类"扩展点**——SPI(`CaptchaVerifier`)+ 配置(`provider` + 密钥块)已就位,接入只需新增一个实现类 + 配 `provider=tencent`,**不动既有代码**。未先实装第三方是因其需真实账号密钥 + 外联才能联调/测,留到具备条件时按 SPI 补即可,不阻塞 v1 上线。

## 6. DDoS 边界(独立线,本文不重复)

- **应用层 L7 限流已在推进**:ADR-019(cross-domain rate limit)、`SlidingWindowRateLimiter`(console)、`TenantActionRateLimiter`(orchestrator)、PR #708(热路径 + 昂贵接口)、PR #694(worker 接入限流 + 配额 + body 上限)。
- **真正的 L3/L4 容量型 DDoS 应用代码挡不住**,必须靠 **CDN / 云 WAF / 网关**(Cloudflare、云 LB、ingress rate-limit + IP 黑名单)——属运维/基础设施,不在本仓代码范围。
- 本登录方案与 DDoS 线**正交**:登录防护是"防针对性猜测",限流是"防量"。

## 7. 落地范围

- **后端(本仓 batch-console-api)** —— **本次已落地**:`LoginFailureTracker`(失败计数 + 退避)+ `LoginProtectionService`(risk-based 触发、不锁账号)+ `CaptchaVerifier` SPI(实装 `none`/`selfhosted`,`tencent`/`aliyun` 扩展点)+ `ConsoleCaptchaController`(`/captcha/config` + `/captcha/challenge`)+ `LoginProtectionProperties`/`CaptchaProperties` 配置 + `ResultCode.CAPTCHA_REQUIRED` + 单测。集成进 `ConsoleLoginService`(密码校验前 captcha 关、失败后记数退避、成功清零)。
- **前端(batch-console 仓)**:按 `/captcha/config` 动态渲染 widget(自建滑块组件 / 天御 SDK)。后端出接口契约。
- **默认全关**(`login-protection.enabled=false` + `captcha.provider=none`),逐环境开启,对现有部署零影响。

## 8. 与现有工作/文档的关系

- 不重复 #708 / ADR-019(限流)、`console-login-encryption.md`(传输加密)、`password-security-backlog.md`(密码策略)。本文是它们之上的**登录端点防暴力破解 + 验证码**补充。
- 实施 PR 落地后,在 `password-security-backlog.md` 或本文回链勾选状态。
