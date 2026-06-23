# 密码安全性补全计划 — P0/P1/P2/P3 Backlog

## 概述

- **现状(commit 79d3f35)**:FE 方案 A 随机生成 + 复制已落地
- **缺口**:租户自助改密码 + 首次强制改密码 + 周期策略
- **工时**:P0/P1(联动)2-3 天 / P2 1 天 / P3 1 天
- **文档**:本计划 + 对应 ADR + 实施 checklist

---

## P0 — 自助改密码(生产必须)

### P0.1 BE — POST /api/console/auth/change-password

**文件**:`batch-console-api/src/main/java/io/github/pinpols/console/api/auth/`

#### 新增 DTO

`ChangePasswordRequest.java`:
```java
@Data
@Validated
public class ChangePasswordRequest {
  @NotBlank(message = "旧密码不能为空")
  @Size(min = 12, max = 256)
  private String oldPassword;

  @NotBlank(message = "新密码不能为空")
  @Size(min = 12, max = 256, message = "新密码长度 12-256 位")
  private String newPassword;

  @NotNull
  @Size(min = 1, max = 256)
  private String confirmPassword;  // 前端验证用,BE 可忽略
}
```

#### 新增 Endpoint

`ConsoleAuthController.java`:
```java
@PostMapping("/api/console/auth/change-password")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_USER', 'ROLE_USER')")
public ResponseEntity<?> changePassword(
  @RequestBody @Validated ChangePasswordRequest req,
  Authentication auth
) {
  String username = ((UserDetails) auth.getPrincipal()).getUsername();
  
  // 1. 验证旧密码
  ConsoleUserAccount account = userAccountService.findByUsername(username);
  if (!passwordEncoder.matches(req.oldPassword, account.getPasswordHash())) {
    return ResponseEntity.status(400).body(new ErrorResponse(
      "INVALID_OLD_PASSWORD",
      "旧密码错误"
    ));
  }
  
  // 2. 禁止新密码等于旧密码
  if (req.oldPassword.equals(req.newPassword)) {
    return ResponseEntity.status(400).body(new ErrorResponse(
      "PASSWORD_SAME",
      "新密码与旧密码相同,请输入不同的密码"
    ));
  }
  
  // 3. 更新密码 + password_must_change = FALSE
  account.setPasswordHash(passwordEncoder.encode(req.newPassword));
  account.setPasswordMustChange(false);
  userAccountService.update(account);
  
  // 4. 可选:记 audit log
  auditService.log(AuditEvent.builder()
    .action("CHANGE_PASSWORD")
    .username(username)
    .status("SUCCESS")
    .build());
  
  return ResponseEntity.ok(Map.of("message", "密码修改成功"));
}
```

**权限**:`@PreAuthorize` 5 角色全收(class-level 改成方法级 hasAnyAuthority)

**返回**:
```json
{
  "message": "密码修改成功"
}
// 错误响应 code: INVALID_OLD_PASSWORD / PASSWORD_SAME / VALIDATE_ERROR
```

#### 单测

`ChangePasswordTest.java`:
```java
@Test
void changePassword_OldPasswordWrong_Return400() {
  api.post("/auth/change-password", Map.of(
    "oldPassword", "wrongPassword123",
    "newPassword", "newPassword456789",
    "confirmPassword", "newPassword456789"
  )).expectStatus(400).expectBody("INVALID_OLD_PASSWORD");
}

@Test
void changePassword_NewSameAsOld_Return400() {
  api.post("/auth/change-password", Map.of(
    "oldPassword", "CurrentPassword123",
    "newPassword", "CurrentPassword123",
    "confirmPassword", "CurrentPassword123"
  )).expectStatus(400).expectBody("PASSWORD_SAME");
}

@Test
void changePassword_Valid_Return200AndClear() {
  api.post("/auth/change-password", Map.of(
    "oldPassword", "CurrentPassword123",
    "newPassword", "NewPassword456789",
    "confirmPassword", "NewPassword456789"
  )).expectStatus(200);
  
  // 验证 password_must_change = false
  var account = db.query("SELECT password_must_change FROM console_user_account WHERE username = ?");
  assert(account.passwordMustChange == false);
}
```

---

### P0.2 FE — /system/me 自助改密码页

**新增**:`src/views/system/MyProfile.vue`

```vue
<template>
  <div class="my-profile">
    <el-page-header title="我的资料">
      <template #content>
        <el-row :gutter="20">
          <!-- 基本信息卡片 -->
          <el-col :xs="24" :sm="12">
            <el-card>
              <template #header>
                <span>账号信息</span>
              </template>
              <el-descriptions :column="1">
                <el-descriptions-item label="用户名">{{ userInfo?.username }}</el-descriptions-item>
                <el-descriptions-item label="邮箱">{{ userInfo?.email || '- 未设置' }}</el-descriptions-item>
                <el-descriptions-item label="角色">{{ userInfo?.authorities?.join(', ') }}</el-descriptions-item>
                <el-descriptions-item label="登录时间">{{ formatDateTime(lastLogin) }}</el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-col>

          <!-- 修改密码卡片 -->
          <el-col :xs="24" :sm="12">
            <el-card>
              <template #header>
                <span>修改密码</span>
              </template>
              <el-form :model="passwordForm" :rules="passwordRules" ref="passwordFormRef">
                <el-form-item label="当前密码" prop="oldPassword">
                  <el-input-password 
                    v-model="passwordForm.oldPassword"
                    placeholder="请输入当前密码" 
                  />
                </el-form-item>
                <el-form-item label="新密码" prop="newPassword">
                  <el-input-password 
                    v-model="passwordForm.newPassword"
                    placeholder="≥ 12 位,含大小写字母、数字、符号" 
                  />
                  <div class="field-hint">强度: {{ passwordStrength }}</div>
                </el-form-item>
                <el-form-item label="确认新密码" prop="confirmPassword">
                  <el-input-password 
                    v-model="passwordForm.confirmPassword"
                    placeholder="请再次输入新密码" 
                  />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="submitChangePassword" :loading="isSubmitting">
                    确认修改
                  </el-button>
                  <el-button @click="resetPasswordForm">取消</el-button>
                </el-form-item>
              </el-form>
            </el-card>
          </el-col>
        </el-row>
      </template>
    </el-page-header>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores'
import { api } from '@/api'

const userStore = useUserStore()
const userInfo = computed(() => userStore.userInfo)
const lastLogin = computed(() => userStore.lastLoginTime)

const passwordFormRef = ref()
const isSubmitting = ref(false)
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const passwordRules = {
  oldPassword: [
    { required: true, message: '请输入当前密码', trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 12, message: '密码至少 12 位', trigger: 'blur' },
    { max: 256, message: '密码最多 256 位', trigger: 'blur' },
    {
      pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[!@#$%^&*\-_=+])/,
      message: '密码需含大小写字母、数字、特殊符号(!@#$%^&*-_=+)',
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

const passwordStrength = computed(() => {
  const pwd = passwordForm.newPassword
  let score = 0
  if (pwd.length >= 16) score += 2; else if (pwd.length >= 12) score += 1
  if (/[a-z]/.test(pwd)) score += 1
  if (/[A-Z]/.test(pwd)) score += 1
  if (/\d/.test(pwd)) score += 1
  if (/[!@#$%^&*\-_=+]/.test(pwd)) score += 1
  
  if (score <= 2) return '弱'
  if (score <= 4) return '中'
  return '强'
})

const submitChangePassword = async () => {
  try {
    await passwordFormRef.value?.validate()
    isSubmitting.value = true

    await api.post('/api/console/auth/change-password', {
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword,
      confirmPassword: passwordForm.confirmPassword,
    })

    ElMessage.success('密码修改成功,请重新登录')
    // 需要重新登录
    userStore.logout()
    location.href = '/login'
  } catch (err) {
    if (err.response?.data?.code === 'INVALID_OLD_PASSWORD') {
      ElMessage.error('当前密码错误')
    } else if (err.response?.data?.code === 'PASSWORD_SAME') {
      ElMessage.error('新密码与旧密码相同')
    } else {
      ElMessage.error('密码修改失败,请重试')
    }
  } finally {
    isSubmitting.value = false
  }
}

const resetPasswordForm = () => {
  passwordFormRef.value?.resetFields()
}
</script>

<style scoped>
.my-profile {
  padding: 20px;
}

.field-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
</style>
```

**路由**:`src/router/index.ts`:
```ts
{
  path: '/system/me',
  component: () => import('@/views/system/MyProfile.vue'),
  meta: { title: '我的资料', icon: 'User' },
}
```

**菜单**:`src/views/system/SystemLayout.vue` 加用户菜单:
```vue
<el-dropdown-menu>
  <el-dropdown-item @click="$router.push('/system/me')">
    📋 我的资料
  </el-dropdown-item>
  <el-dropdown-item @click="logout">
    🚪 退出登录
  </el-dropdown-item>
</el-dropdown-menu>
```

---

### P0.3 FE — API stub

**编辑**:`src/api/auth.ts`:
```ts
export const changePassword = (req: {
  oldPassword: string
  newPassword: string
  confirmPassword?: string
}) => {
  return api.post('/api/console/auth/change-password', req)
}
```

---

## P1 — 首次强制改密码

### P1.1 BE — 加字段 + 标志

**SQL**:
```sql
ALTER TABLE console_user_account 
ADD COLUMN password_must_change BOOLEAN DEFAULT TRUE NOT NULL,
ADD COLUMN password_changed_at TIMESTAMP DEFAULT NULL;
```

**Entity**:`ConsoleUserAccount.java`:
```java
@Column(name = "password_must_change")
private Boolean passwordMustChange = true;

@Column(name = "password_changed_at")
private LocalDateTime passwordChangedAt;
```

**修改 createUser / resetPassword 逻辑**:
```java
// 创建用户或重置密码时,自动置 true
account.setPasswordMustChange(true);
account.setPasswordChangedAt(null);
accountService.save(account);
```

**修改 changePassword**:
```java
account.setPasswordMustChange(false);
account.setPasswordChangedAt(LocalDateTime.now());
accountService.save(account);
```

**修改 /auth/login response**:
```java
return ResponseEntity.ok(new ConsoleAuthProfile(
  token,
  refreshToken,
  user.getUsername(),
  user.getAuthorities(),
  user.getEmail(),
  user.getPasswordMustChange()  // ← 新增字段
));
```

---

### P1.2 FE — 路由 guard 强制改密码

**编辑**:`src/router/guards.ts`:
```ts
router.beforeEach((to, from, next) => {
  const userStore = useUserStore()
  
  // 如果必须改密码,拦截到 /change-password-required
  if (userStore.userInfo?.passwordMustChange) {
    if (to.path !== '/change-password-required') {
      return next('/change-password-required')
    }
  }
  
  next()
})
```

**新增**:`src/views/auth/ChangePasswordRequired.vue`:
```vue
<template>
  <div class="change-password-required">
    <el-empty description="系统提示">
      <template #default>
        <p class="title">首次登录,请修改密码</p>
        <p class="desc">为了账号安全,首次登录必须修改管理员设置的初始密码</p>
      </template>
    </el-empty>
    <el-dialog title="修改密码" :model-value="true" :close-on-click-modal="false" :show-close="false">
      <!-- 同 MyProfile.vue 的密码修改表单 -->
      <ChangePasswordForm @success="onSuccess" @skip="onSkip" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import ChangePasswordForm from '@/views/system/components/ChangePasswordForm.vue'

const router = useRouter()
const onSuccess = () => {
  ElMessage.success('密码已修改')
  router.push('/system/dashboard')
}
const onSkip = () => {
  // 可选:给个 5 分钟后再改的宽限
  ElMessage.warning('密码修改提醒:在登出前必须修改密码')
  router.push('/system/dashboard')
}
</script>

<style scoped>
.change-password-required {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
}
.title { font-weight: 600; font-size: 18px; }
.desc { color: #909399; font-size: 14px; }
</style>
```

**API stub**:`src/api/auth.ts` 加:
```ts
export const fetchAuthProfile = () => 
  api.get('/api/console/auth/me')
  // 返回 { token, userInfo: { ..., passwordMustChange: boolean } }
```

---

## P2 — 密码修改通知

### P2.1 BE — reset-password 触发邮件/短信

**编辑**:`ConsoleUserAccountController.resetPassword()`:
```java
@PostMapping("/api/console/users/{userId}/reset-password")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
public ResponseEntity<?> resetPassword(
  @PathVariable Long userId,
  @RequestBody ResetPasswordRequest req
) {
  var account = userAccountService.findById(userId);
  var newPassword = req.password; // admin 指定或后端生成
  
  account.setPasswordHash(passwordEncoder.encode(newPassword));
  account.setPasswordMustChange(true);
  userAccountService.save(account);
  
  // ★ 新增:触发通知
  notificationService.sendPasswordReset(
    account.getEmail(),
    account.getUsername(),
    newPassword  // ← 仅发一次,BE 内存,不落 DB
  );
  
  return ResponseEntity.ok(Map.of("message", "密码已重置"));
}
```

**通知渠道**:使用现有 `notification-channels` 配置:
- **邮件模板** `password-reset.txt`:
  ```
  您的密码已被管理员重置,临时密码:{PASSWORD}
  首次登录后请立即修改为强密码
  ```
- **短信模板** `password_reset_sms`:
  ```
  您的 {SYSTEM_NAME} 账号密码已重置为:{PASSWORD}。请勿转发他人。
  ```

---

## P3 — 密码周期策略(等保 2.0 三级)

### P3.1 BE — 字段 + 过期检查

**SQL**:
```sql
ALTER TABLE console_user_account 
ADD COLUMN password_expires_at TIMESTAMP NOT NULL 
  DEFAULT (CURRENT_TIMESTAMP + INTERVAL 90 DAY);

CREATE TABLE password_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  password_hash VARCHAR(256) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(user_id) REFERENCES console_user_account(id),
  UNIQUE KEY uq_user_pwd (user_id, password_hash)  -- 防 5 次内重用
);
```

**验证逻辑**:`ConsoleSecurityConfiguration.java`:
```java
@Component
public class PasswordExpiryValidator implements UserDetailsChecker {
  public void check(UserDetails user) {
    var account = (ConsoleUserAccount) user;
    if (account.getPasswordExpiresAt().isBefore(LocalDateTime.now())) {
      throw new CredentialsExpiredException("密码已过期,请修改");
    }
  }
}
```

**修改密码时检查历史**:
```java
public void changePassword(String username, String newPassword) {
  var account = findByUsername(username);
  var recentPasswords = passwordHistoryService.getLast5(account.getId());
  
  for (var hist : recentPasswords) {
    if (passwordEncoder.matches(newPassword, hist.getPasswordHash())) {
      throw new ValidationException("密码已在最近 5 次使用过,请输入新密码");
    }
  }
  
  // 保存新密码哈希到历史
  account.setPasswordHash(passwordEncoder.encode(newPassword));
  account.setPasswordExpiresAt(LocalDateTime.now().plusDays(90));
  account.setPasswordMustChange(false);
  save(account);
  
  passwordHistoryService.record(account.getId(), account.getPasswordHash());
}
```

### P3.2 FE — banner 提醒

**新增**:`src/components/PasswordExpiryBanner.vue`:
```vue
<template>
  <el-alert
    v-if="daysLeft <= 7 && daysLeft > 0"
    type="warning"
    title="密码即将过期"
    :description="`您的密码将在 ${daysLeft} 天后过期,建议立即修改`"
    @close="dismissBanner"
    closable
  >
    <template #default>
      <el-button type="text" @click="$router.push('/system/me')">现在修改</el-button>
    </template>
  </el-alert>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/stores'
import { dayjs } from '@/utils/datetime'

const userStore = useUserStore()
const daysLeft = computed(() => {
  const expiresAt = userStore.userInfo?.passwordExpiresAt
  if (!expiresAt) return null
  return dayjs(expiresAt).diff(dayjs(), 'day')
})
</script>
```

**植入**:`src/layouts/DefaultLayout.vue`:
```vue
<template>
  <div class="app-layout">
    <PasswordExpiryBanner />
    <!-- 其余布局 -->
  </div>
</template>
```

---

## 跟踪表格 — 实施 Checklist

| 项 | BE | FE | 状态 | 负责人 | ETA |
|---|---|---|---|---|---|
| **P0** |
| changePassword API | ✅ DTO + endpoint + 单测 | - | todo | BE | 2026-05-20 |
| /system/me 页面 + 路由 | - | ✅ MyProfile.vue + guard | todo | FE | 2026-05-20 |
| **P1** |
| password_must_change 字段 + DDL | ✅ (参考 SQL) | - | todo | BE | 2026-05-21 |
| /auth/login 返回 mustChangePassword | ✅ (参考 code) | - | todo | BE | 2026-05-21 |
| 路由 guard 强制跳转 | - | ✅ (参考 guards.ts) | todo | FE | 2026-05-21 |
| /change-password-required 强制页面 | - | ✅ (参考 vue) | todo | FE | 2026-05-21 |
| **P2** |
| reset-password 触发通知 | ✅ (参考 code) | - | todo | BE | 2026-05-22 |
| 邮件/短信模板 | ✅ (参考 template) | - | todo | BE/Ops | 2026-05-22 |
| **P3** |
| password_expires_at 字段 + DDL | ✅ (参考 SQL) | - | todo | BE | 2026-05-23 |
| password_history 表 + 检查 | ✅ (参考 code) | - | todo | BE | 2026-05-23 |
| PasswordExpiryBanner | - | ✅ (参考 vue) | todo | FE | 2026-05-23 |

---

## 参考文档

- **ADR-031** — 密码安全策略(待写)
- **OWASP** — [Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- **等保 2.0** — 三级密码周期 90 天 + 历史不重用 5 次
- **IEC 27001** — 账户管理 A.9.2

---

## 执行路径

```
Week 1:
├─ Mon (2026-05-20):  P0 BE + P0 FE 并行(8h)
├─ Tue (2026-05-21):  P1 BE + P1 FE 并行(8h)
├─ Wed (2026-05-22):  P2 BE 通知 + 邮件模板(4h)
└─ Thu (2026-05-23):  P3 BE 周期 + FE banner(4h) + 全量 e2e 重跑

Week 2:
├─ Pro 档 k6 压测 + 混沌(可选,生产前做)
└─ 生产上线前审核签字
```

---

**批准人**:product owner / security champion  
**首版日期**:2026-05-18  
**最后更新**:2026-05-18
