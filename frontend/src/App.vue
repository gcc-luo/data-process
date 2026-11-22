<script setup>
import { computed, onMounted, provide, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { login, authorizedRequest } from './auth.js'
import DataSources from './DataSources.vue'
import TaskStudio from './TaskStudio.vue'
import ProcessingTasks from './ProcessingTasks.vue'
import Organization from './Organization.vue'
import DataExplorer from './DataExplorer.vue'
import Reports from './Reports.vue'
import Services from './Services.vue'
import Realtime from './Realtime.vue'
import { menuIconPath } from './menu-icons.js'

const status = ref('正在检查')
const user = ref(null)
const grantedPermissions = ref([])
const canPermission = permission => grantedPermissions.value.includes(permission)
provide('canPermission', canPermission)
const sessionLoading = ref(true)
const username = ref('')
const password = ref('')
const rememberUsername = ref(false)
const captchaRequired = ref(false)
const captchaCode = ref('')
const captchaStamp = ref(0)
const profileDialog = ref(false)
const profileMode = ref('profile')
const forcePassword = ref(false)
const profileInfo = ref({})
const profileForm = ref({ oldPassword: '', newPassword: '', confirm: '' })
const profileMessage = ref('')
const loginError = ref('')
const loggingIn = ref(false)
async function loadSession() {
  const response = await fetch('/api/session', { credentials: 'same-origin' })
  if (response.ok) {
    const session = await response.json()
    user.value = session.username
    profileInfo.value = session
    grantedPermissions.value = Array.isArray(session.permissions) ? session.permissions : []
    forcePassword.value = Boolean(session.mustChangePassword)
    if (forcePassword.value) { profileMode.value = 'password'; profileDialog.value = true }
  } else {
    user.value = null
    grantedPermissions.value = []
  }
  if (response.ok) await loadNavigation()
  else navigation.value = []
}
async function signIn() {
  loggingIn.value = true; loginError.value = ''
  try {
    try {
      if (rememberUsername.value) localStorage.setItem('governance-platform-username', username.value)
      else localStorage.removeItem('governance-platform-username')
    } catch { /* Storage may be unavailable in private browsing. */ }
    await login(username.value, password.value, captchaCode.value); password.value = ''; captchaCode.value = ''; captchaRequired.value = false; await loadSession()
  }
  catch (error) {
    loginError.value = error.message
    if (error.captchaRequired) { captchaRequired.value = true; captchaStamp.value = Date.now() }
  }
  finally { loggingIn.value = false }
}
function showForgotPassword() { void ElMessageBox.alert('请联系平台管理员重置账号密码。', '找回密码', { confirmButtonText: '知道了' }) }
async function openProfile(mode = 'profile') {
  profileMode.value = forcePassword.value ? 'password' : mode
  profileMessage.value = ''
  profileForm.value = { oldPassword: '', newPassword: '', confirm: '' }
  try {
    const response = await fetch('/api/session', { credentials: 'same-origin' })
    if (response.ok) {
      const session = await response.json()
      profileInfo.value = session
    }
  } catch { /* Keep the cached identity when the session cannot be refreshed. */ }
  profileDialog.value = true
}
function fmtTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }
function fmtEpoch(value) {
  if (!value) return '—'
  const d = new Date(value)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
function onAccountCommand(command) { void openProfile(command === 'password' ? 'password' : 'profile') }
async function submitProfile() {
  profileMessage.value = ''
  if (profileForm.value.newPassword !== profileForm.value.confirm) { profileMessage.value = '两次输入的新密码不一致'; return }
  try {
    const response = await authorizedRequest('/api/session/password', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ oldPassword: profileForm.value.oldPassword, newPassword: profileForm.value.newPassword }) })
    if (!response.ok) {
      const payload = await response.json().catch(() => null)
      throw new Error(payload?.error || '修改密码失败')
    }
    forcePassword.value = false
    profileDialog.value = false
    profileForm.value = { oldPassword: '', newPassword: '', confirm: '' }
    void ElMessageBox.alert('密码已修改，其他设备的登录会话已失效。', '修改成功', { confirmButtonText: '知道了' })
  } catch (error) { profileMessage.value = error.message }
}
async function signOut() {
  if (!(await canLeave())) return
  try { const response = await authorizedRequest('/api/logout', { method: 'POST' }); if (!response.ok && response.status !== 401) throw new Error(); user.value = null; grantedPermissions.value = []; navigation.value = []; section.value = '' }
  catch { loginError.value = '退出失败，请检查服务连接' }
}

const section = ref('')
const mobileNavigationOpen = ref(false)
const navigation = ref([])
const menu = computed(() => {
  const build = parentId => navigation.value.filter(item => item.parentId === parentId).map(item => {
    const children = build(item.id)
    const normalized = { ...item, label: item.name, icon: menuIconPath(item.icon) }
    if (children.length) normalized.children = children
    return normalized
  })
  return build(null)
})
const groupPages = ref({})
const expandedGroups = ref({})
const pageKey = component => { const value = String(component || ''); const index = value.indexOf('/'); return index >= 0 ? value.slice(index + 1) : value }
const sectionItem = computed(() => menu.value.find(item => item.component === section.value))
const sectionLabel = computed(() => sectionItem.value?.label ?? '工作空间')
const sectionChildren = computed(() => sectionItem.value?.children || [])
const activeChildLabel = computed(() => sectionChildren.value.find(child => pageKey(child.component) === groupPages.value[section.value])?.label ?? '')
const pendingMenus = new Set(['overview', 'operations'])
async function loadNavigation() {
  try {
    const response = await fetch('/api/navigation', { credentials: 'same-origin' })
    if (!response.ok) { navigation.value = []; section.value = ''; return }
    const rows = await response.json()
    if (!Array.isArray(rows)) return
    navigation.value = rows
    if (!rows.some(item => item.component === section.value)) {
      const first = rows.find(item => item.parentId == null)
      section.value = first ? first.component : ''
    }
    for (const parent of rows.filter(item => item.parentId == null)) {
      const children = rows.filter(item => item.parentId === parent.id)
      if (children.length && !children.some(child => pageKey(child.component) === groupPages.value[parent.component])) groupPages.value[parent.component] = pageKey(children[0].component)
    }
  } catch { navigation.value = []; section.value = '' }
}
const taskStudio = ref(null)
const processingTasks = ref(null)
async function canLeave() {
  const dirtyWorkflow = section.value === 'tasks' && taskStudio.value?.hasUnsavedWorkflow()
  const dirtyProcessing = section.value === 'processing' && processingTasks.value?.hasUnsaved?.()
  if (!dirtyWorkflow && !dirtyProcessing) return true
  try { await ElMessageBox.confirm('当前编排尚未保存，离开后修改将丢失。', '确认离开', { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' }); return true } catch { return false }
}
async function navigate(item) {
  if (item.children) {
    if (section.value !== item.component) {
      if (!(await canLeave())) return
      section.value = item.component
      if (!groupPages.value[item.component]) groupPages.value[item.component] = pageKey(item.children[0]?.component)
      expandedGroups.value[item.component] = true
    } else expandedGroups.value[item.component] = !expandedGroups.value[item.component]
    return
  }
  const target = typeof item === 'string' ? item : item.component
  if (target !== section.value && await canLeave()) { section.value = target; expandedGroups.value = {}; mobileNavigationOpen.value = false }
  else if (target === section.value) mobileNavigationOpen.value = false
}
async function navigateChild(parent, child) {
  if (section.value !== parent.component && !(await canLeave())) return
  section.value = parent.component
  groupPages.value[parent.component] = pageKey(child.component)
  expandedGroups.value[parent.component] = true
  mobileNavigationOpen.value = false
}
onMounted(async () => {
  window.addEventListener('governance:session-expired', () => { user.value = null; grantedPermissions.value = []; navigation.value = []; section.value = ''; loginError.value = '登录已失效，请重新登录' })
  try { const remembered = localStorage.getItem('governance-platform-username'); if (remembered) { username.value = remembered; rememberUsername.value = true } } catch { /* Storage may be unavailable in private browsing. */ }
  try { await loadSession() } catch { user.value = null } finally { sessionLoading.value = false }
  try { const response = await fetch('/api/health'); if (!response.ok || (await response.json()).status !== 'UP') throw new Error(); status.value = '服务正常' }
  catch { status.value = '服务未连接' }
})
</script>

<template>
  <main v-if="sessionLoading" class="login-screen login-loading"><div class="login-loading-card">正在检查登录状态…</div></main>
  <main v-else-if="!user" class="login-screen">
    <form class="login-card" @submit.prevent="signIn">
      <div class="card-brand"><svg class="brand-cube" viewBox="0 0 48 48" aria-hidden="true"><path fill="#55D6F0" d="M24 2 43 13v22L24 46 5 35V13L24 2Z"/><path fill="#1685E7" d="M24 2v22L5 13 24 2Z"/><path fill="#36BFE8" d="M24 24 43 13v22L24 46V24Z"/><path fill="#195CB8" d="M5 13 24 24v22L5 35V13Z"/><path fill="#D8F8FF" d="m24 15 8 4.5v9L24 33l-8-4.5v-9l8-4.5Z"/></svg><strong>DataSphere</strong></div>
      <h2>数据治理平台</h2>
      <p class="card-subtitle">Data Governance Platform</p>
      <el-input v-model="username" autocomplete="username" placeholder="用户名" aria-label="用户名" required><template #prefix><svg class="field-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/></svg></template></el-input>
      <el-input v-model="password" type="password" autocomplete="current-password" placeholder="密码" aria-label="密码" required show-password><template #prefix><svg class="field-icon" viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/></svg></template></el-input>
      <div v-if="captchaRequired" class="captcha-row"><el-input v-model="captchaCode" placeholder="验证码" maxlength="4" aria-label="验证码" /><img :src="'/api/captcha?t=' + captchaStamp" alt="验证码" title="点击刷新" @click="captchaStamp = Date.now()" /></div>
      <div class="login-options"><el-checkbox v-model="rememberUsername">记住我</el-checkbox><button type="button" class="forgot-link" @click="showForgotPassword">忘记密码？</button></div>
      <el-alert v-if="loginError" :title="loginError" type="error" :closable="false" />
      <el-button type="primary" native-type="submit" :loading="loggingIn">登录</el-button>
      <div class="login-security"><svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.5 13 3.6v3.8c0 3.1-2.1 5.7-5 7.1-2.9-1.4-5-4-5-7.1V3.6L8 1.5Z"/><path d="m5.7 7.8 1.5 1.5 3.2-3.2"/></svg><span>安全连接 · 访问行为受平台策略保护</span></div>
    </form>
  </main>
  <main v-else class="shell" @keydown.esc.window="mobileNavigationOpen = false">
    <button v-if="mobileNavigationOpen" class="nav-backdrop" type="button" aria-label="关闭导航菜单" @click="mobileNavigationOpen = false"></button>
    <aside class="sidebar" :class="{ 'mobile-open': mobileNavigationOpen }">
      <div class="brand"><svg class="brand-cube" viewBox="0 0 48 48" aria-hidden="true"><path fill="#55D6F0" d="M24 2 43 13v22L24 46 5 35V13L24 2Z"/><path fill="#1685E7" d="M24 2v22L5 13 24 2Z"/><path fill="#36BFE8" d="M24 24 43 13v22L24 46V24Z"/><path fill="#195CB8" d="M5 13 24 24v22L5 35V13Z"/><path fill="#D8F8FF" d="m24 15 8 4.5v9L24 33l-8-4.5v-9l8-4.5Z"/></svg><div><strong>DataSphere</strong><small>DATA OPERATIONS</small></div></div>
      <nav class="primary-nav" aria-label="平台导航">
        <div v-for="item in menu" :key="item.component" class="nav-group">
          <button type="button" :class="{ active: section === item.component, 'has-children': !!item.children, expanded: !!item.children && expandedGroups[item.component] }" :aria-current="section === item.component ? 'page' : undefined" :aria-expanded="item.children ? !!expandedGroups[item.component] : undefined" @click="navigate(item)">
            <span class="nav-leading"><svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true"><path :d="item.icon" /></svg><span>{{ item.label }}</span></span>
            <span v-if="pendingMenus.has(item.component)" class="pending">待接入</span>
            <svg v-else-if="item.children" class="nav-chevron" viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5" /></svg>
          </button>
          <div v-if="item.children && expandedGroups[item.component]" class="submenu" :aria-label="`${item.label}子菜单`">
            <button v-for="child in item.children" :key="child.component" type="button" :class="{ active: section === item.component && groupPages[item.component] === pageKey(child.component) }" :aria-current="section === item.component && groupPages[item.component] === pageKey(child.component) ? 'page' : undefined" @click.stop="navigateChild(item, child)">
              <svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true"><path :d="child.icon" /></svg><span>{{ child.label }}</span>
            </button>
          </div>
        </div>
      </nav>
    </aside>
    <section class="workspace">
      <header>
        <div class="header-leading">
          <button class="mobile-nav-trigger" type="button" aria-label="打开导航菜单" :aria-expanded="mobileNavigationOpen" @click="mobileNavigationOpen = !mobileNavigationOpen"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 5h14M3 10h14M3 15h14" /></svg></button>
          <div class="breadcrumbs"><span>工作空间</span><span class="slash">/</span><strong>{{ sectionLabel }}</strong><template v-if="sectionChildren.length"><span class="slash">/</span><strong>{{ activeChildLabel }}</strong></template></div>
        </div>
        <div class="header-actions">
          <span class="status"><i :class="{ offline: status !== '服务正常' }"></i>{{ status }}</span>
          <el-dropdown trigger="click" @command="onAccountCommand">
            <button type="button" class="account-chip-button" title="账号菜单"><span class="account-chip"><span class="account-avatar">{{ user?.slice(0, 1)?.toUpperCase() }}</span><span>{{ user }}</span><svg class="account-caret" viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5"/></svg></span></button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="password">修改密码</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <button class="logout-button" type="button" @click="signOut"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M8 4H4.8A1.8 1.8 0 0 0 3 5.8v8.4A1.8 1.8 0 0 0 4.8 16H8m3-9 4 3-4 3m4-3H7" /></svg><span>退出</span></button>
        </div>
      </header>
      <div v-if="section === 'assets'" class="content"><DataSources :view="groupPages.assets || 'sources'" /></div>
      <div v-else-if="section === 'processing'" class="content"><ProcessingTasks ref="processingTasks" :view="groupPages.processing || 'tasks'" /></div>
      <div v-else-if="section === 'tasks'" class="content"><TaskStudio ref="taskStudio" /></div>
      <div v-else-if="section === 'system'" class="content"><Organization :view="groupPages.system || 'departments'" @navigation-updated="loadSession" /></div>
      <div v-else-if="section === 'query'" class="content"><DataExplorer /></div>
      <div v-else-if="section === 'reports'" class="content"><Reports /></div>
      <div v-else-if="section === 'services'" class="content"><Services /></div>
      <div v-else-if="section === 'realtime'" class="content"><Realtime /></div>
      <div v-else class="content"><div class="unavailable"><h2>{{ section ? '功能尚未接入' : '当前账号没有可访问的模块' }}</h2><p>{{ section ? '该模块仍在开发。' : '请联系平台管理员为你的角色分配相应的模块操作权限。' }}</p></div></div>
    </section>
    <el-dialog v-model="profileDialog" :title="forcePassword ? '修改初始密码' : (profileMode === 'password' ? '修改密码' : '个人中心')" width="min(620px, 95vw)" :show-close="!forcePassword" :close-on-click-modal="!forcePassword" :close-on-press-escape="!forcePassword">
      <el-alert v-if="forcePassword" title="首次登录或密码被管理员重置后，需修改密码才能继续使用平台。" type="warning" :closable="false" show-icon style="margin-bottom:14px" />
      <el-alert v-if="profileMessage" :title="profileMessage" type="error" :closable="false" show-icon style="margin-bottom:14px" />
      <el-descriptions v-if="profileMode === 'profile' && !forcePassword" :column="2" border>
        <el-descriptions-item label="账号">{{ profileInfo.username }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ profileInfo.nickname }}</el-descriptions-item>
        <el-descriptions-item label="所属部门">{{ profileInfo.department || '—' }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ (profileInfo.roles || []).join('、') || '—' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ profileInfo.email || '—' }}</el-descriptions-item>
        <el-descriptions-item label="手机">{{ profileInfo.phone || '—' }}</el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ fmtTime(profileInfo.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="上次登录">{{ fmtTime(profileInfo.prevLoginAt) }}</el-descriptions-item>
        <el-descriptions-item label="本次登录">{{ fmtEpoch(profileInfo.loginAt) }}</el-descriptions-item>
        <el-descriptions-item label="登录 IP">{{ profileInfo.loginIp || '—' }}</el-descriptions-item>
        <el-descriptions-item label="终端" :span="2">{{ profileInfo.userAgent || '—' }}</el-descriptions-item>
      </el-descriptions>
      <el-form v-else label-position="top" @submit.prevent="submitProfile">
        <el-form-item label="原密码" required><el-input v-model="profileForm.oldPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="新密码" required><el-input v-model="profileForm.newPassword" type="password" show-password autocomplete="new-password" placeholder="至少8位，含大小写/数字/特殊字符中三类" /></el-form-item>
        <el-form-item label="确认新密码" required><el-input v-model="profileForm.confirm" type="password" show-password autocomplete="new-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="!forcePassword" @click="profileDialog = false">关闭</el-button>
        <el-button v-if="profileMode === 'profile' && !forcePassword" type="primary" @click="profileMode = 'password'">修改密码</el-button>
        <el-button v-else type="primary" @click="submitProfile">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>
<style>
/* 表格行操作按钮：删除（danger）红色，其余统一主题蓝；收紧间距与内边距 */
.el-table .cell .el-button.is-text:not(.el-button--danger):not(.is-disabled),.el-table .cell .el-button.is-link:not(.el-button--danger):not(.is-disabled){color:var(--el-color-primary)}
.el-table .cell .el-button.is-text:not(.el-button--danger):not(.is-disabled):hover,.el-table .cell .el-button.is-link:not(.el-button--danger):not(.is-disabled):hover{color:var(--el-color-primary-light-3)}
/* User-provided data governance background */
.login-screen{position:relative;isolation:isolate;overflow:hidden;min-height:100vh;min-height:100svh;display:flex;align-items:center;justify-content:flex-end;padding:32px clamp(24px,3vw,64px);background:#123f78;color:#173654}
.login-screen::before,.login-screen::after{content:"";position:absolute;inset:0;pointer-events:none}
.login-screen::before{z-index:-2;inset:-1.3%;background:url("/images/login-background-previous.png") center center/cover no-repeat;animation:login-backdrop-drift 48s ease-in-out infinite alternate;will-change:transform}
.login-screen::after{z-index:-1;background:linear-gradient(90deg,transparent 55%,rgba(237,248,252,.08) 100%)}
.login-card{width:min(28vw,520px);min-width:380px;margin-left:auto;padding:28px clamp(24px,2.2vw,38px) 20px;display:flex;flex-direction:column;justify-content:flex-start;gap:13px;border:1px solid rgba(255,255,255,.94);border-radius:18px;background:rgba(255,255,255,.97);box-shadow:0 22px 58px rgba(11,45,88,.22),0 3px 10px rgba(20,73,126,.08);backdrop-filter:blur(12px);animation:login-card-in .55s cubic-bezier(.2,.72,.2,1) both}
.card-brand{display:flex;align-items:center;gap:10px;margin-bottom:2px;color:#122f4c}.card-brand .brand-cube{display:block;width:36px;height:36px;flex:0 0 36px}.card-brand strong{font-size:19px;font-weight:700;letter-spacing:-.02em}.login-card h2{margin:14px 0 0;color:#102b48;font-size:clamp(24px,2vw,30px);font-weight:720;line-height:1.2;letter-spacing:.02em}.card-subtitle{margin:0 0 12px;color:#677b91;font-size:15px;line-height:1.4}
.login-card .el-input{width:100%}.login-card .el-input__wrapper{min-height:44px;padding:0 14px;border:1px solid #d9e2eb;border-radius:9px;background:rgba(255,255,255,.96);box-shadow:0 1px 3px rgba(34,72,110,.04),0 0 0 1px rgba(221,231,239,.35) inset;transition:border-color .18s ease,box-shadow .18s ease}.login-card .el-input__wrapper:hover{border-color:#a9c9e8;box-shadow:0 0 0 3px rgba(48,134,227,.08)}.login-card .el-input__wrapper.is-focus{border-color:#4594ed;box-shadow:0 0 0 3px rgba(48,134,227,.12)}.login-card .el-input__inner{color:#1d3348;font-size:14px}.login-card .el-input__inner::placeholder{color:#8493a3}.field-icon{display:block;width:18px;height:18px;fill:none;stroke:#223b50;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}.login-card .el-input__prefix{margin-right:8px}
.login-options{display:flex;align-items:center;justify-content:space-between;margin:-2px 0 1px}.login-options .el-checkbox__label{color:#34485d;font-size:13px}.login-options .el-checkbox__inner{width:15px;height:15px;border-color:#9eb2c3}.forgot-link{padding:4px 0;border:0;background:transparent;color:#1671eb;font:inherit;font-size:13px;font-weight:600;cursor:pointer}.forgot-link:hover{text-decoration:underline}.login-card .el-button--primary{width:100%;height:44px;margin-top:4px;border:0;border-radius:9px;background:linear-gradient(105deg,#0769f5 0%,#3589fb 100%);box-shadow:0 7px 16px rgba(17,112,244,.22);font-size:15px;font-weight:650;letter-spacing:.08em;transition:filter .18s ease,transform .18s ease}.login-card .el-button--primary:hover{filter:brightness(1.04);transform:translateY(-1px)}.login-security{display:flex;align-items:center;justify-content:center;gap:7px;margin-top:10px;color:#72879a;font-size:10px}.login-security svg{display:block;width:13px;height:13px;flex:0 0 13px;fill:none;stroke:#368b9d;stroke-width:1.3;stroke-linecap:round;stroke-linejoin:round}
.login-loading{justify-content:center}.login-loading-card{padding:18px 26px;border:1px solid rgba(255,255,255,.88);border-radius:12px;background:rgba(255,255,255,.94);color:#536c80;font-size:14px;box-shadow:0 12px 36px rgba(13,58,111,.18)}
.captcha-row{display:flex;gap:10px;align-items:center}.captcha-row img{width:108px;height:36px;flex:none;border:1px solid #d9e2eb;border-radius:8px;cursor:pointer;background:#f2f6fc}
.account-chip-button{border:0;background:transparent;padding:0;font:inherit;color:inherit;cursor:pointer}
.account-caret{width:14px;height:14px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round;opacity:.7}
@keyframes login-backdrop-drift{0%{transform:scale(1)}100%{transform:scale(1.025)}}@keyframes login-card-in{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}
@media(max-width:1120px){.login-card{width:min(40vw,460px);min-width:360px;padding-right:28px;padding-left:28px}}
@media(max-width:820px){.login-screen{justify-content:center;padding:22px}.login-screen::before{background-size:auto 124%;background-position:80% 56%}.login-card{width:min(100%,420px);min-width:0;min-height:0;margin:auto;padding:28px 26px;border-radius:16px}.login-card h2{font-size:26px}.card-subtitle{font-size:15px}}
@media(max-width:480px){.login-screen{padding:16px}.login-screen::before{background-position:84% 58%}.login-card{padding:24px 20px;border-radius:14px}.login-card h2{font-size:24px}.card-subtitle{font-size:14px}.login-card .el-input__wrapper,.login-card .el-button--primary{min-height:44px;height:44px}}
@media(prefers-reduced-motion:reduce){.login-screen::before,.login-card{animation:none}.login-card .el-button--primary,.login-card .el-input__wrapper{transition:none}}
*{box-sizing:border-box}body{margin:0;background:#f5f7fb;color:#243d3a;font-family:"PingFang SC","Microsoft YaHei",sans-serif}button{font:inherit}.shell{min-height:100vh;display:flex;background:#f5f7fb}.sidebar{width:252px;background:linear-gradient(180deg,#172847 0%,#14223f 100%);color:#dce6ff;padding:26px 16px 18px;border-right:1px solid #243656;flex-shrink:0;display:flex;flex-direction:column}.brand{display:flex;gap:12px;align-items:center;padding:0 8px}.brand .brand-cube{display:block;width:40px;height:40px;flex:0 0 40px}.brand strong{display:block;color:#f5f8ff;font-size:15px;font-weight:700;letter-spacing:.02em}.brand small{display:block;color:#9aadd3;font-size:9px;letter-spacing:1.15px;font-weight:650;margin-top:5px}.primary-nav{display:grid;gap:5px;margin-top:42px}.primary-nav button{position:relative;min-height:46px;border:0;background:transparent;color:#c4d2ef;text-align:left;padding:0 11px;border-radius:10px;cursor:pointer;display:flex;align-items:center;justify-content:space-between;transition:background .18s ease,color .18s ease,transform .18s ease}.nav-leading{display:flex;align-items:center;gap:12px;min-width:0;font-size:13px;font-weight:560}.nav-icon{width:18px;height:18px;flex:0 0 18px;fill:none;stroke:currentColor;stroke-width:1.65;stroke-linecap:round;stroke-linejoin:round;opacity:.83}.primary-nav button:hover{background:#203557;color:#f6f8ff}.primary-nav button:active{transform:scale(.985)}.primary-nav button.active{background:#2a467f;color:#f7f9ff;box-shadow:inset 3px 0 0 #9ab7ff,0 4px 12px rgba(4,13,34,.12)}.primary-nav button.active .nav-icon{opacity:1}.pending{flex:0 0 auto;font-size:10px;line-height:1;padding:5px 7px;border-radius:6px;background:rgba(255,255,255,.09);color:#b4c2e3;letter-spacing:.01em}.workspace{min-width:0;flex:1;background:#f5f7fb}.workspace>header{min-height:66px;background:rgba(255,255,255,.92);border-bottom:1px solid #e5ece8;padding:0 34px;display:flex;align-items:center;justify-content:space-between;color:#81918b;font-size:12px}.breadcrumbs{display:flex;align-items:center;gap:9px}.breadcrumbs strong{color:#405a52;font-weight:650}.slash{color:#b6c2bc}.header-actions{display:flex;align-items:center;gap:18px}.status{display:flex;align-items:center;gap:7px;color:#71877e;font-size:12px}.status i{height:7px;width:7px;border-radius:50%;background:#58a582;box-shadow:0 0 0 3px #eaf4ed}.status i.offline{background:#d19857;box-shadow:0 0 0 3px #f8f0e4}.account-chip{display:flex;align-items:center;gap:8px;padding-left:17px;border-left:1px solid #e8eeea;color:#455e55;font-size:12px;font-weight:600}.account-avatar{width:27px;height:27px;border-radius:9px;display:grid;place-items:center;background:#eaf2ed;color:#3d7968;font-size:11px;font-weight:750}.logout-button{display:inline-flex;align-items:center;justify-content:center;gap:7px;padding:8px 11px;border:1px solid #dfe8e2;border-radius:9px;background:#fff;color:#697c74;font-size:12px;font-weight:600;cursor:pointer;transition:background .18s ease,border-color .18s ease,color .18s ease,transform .18s ease}.logout-button svg{width:15px;height:15px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}.logout-button:hover{background:#fff4f1;border-color:#f0d5cf;color:#a65c50}.logout-button:active{transform:translateY(1px)}.primary-nav button:focus-visible,.logout-button:focus-visible{outline:3px solid rgba(75,143,119,.24);outline-offset:2px}.content{max-width:1500px;margin:auto;padding:40px 36px}.page-heading{display:flex;justify-content:space-between;align-items:end;gap:20px;margin-bottom:24px}.kicker{font-size:13px;color:#63867e;margin:0 0 8px}.page-heading h1,.content>h1{font-size:28px;margin:0;font-weight:650}.subheading{margin:9px 0 0;color:#71838a;font-size:14px}.feedback{margin-top:12px}.editor{display:grid;grid-template-columns:minmax(0,1fr) 310px;border:1px solid #dae5e5;background:white;margin-top:22px;min-height:510px}.diagram{background:#fbfdfc;min-width:0}.panel-head{padding:23px 25px;border-bottom:1px solid #e6eeed;display:flex;justify-content:space-between;gap:12px}.panel-head h2{font-size:16px;margin:0}.panel-head p{font-size:12px;color:#8a999d;margin:7px 0 0}.count{font-size:12px;color:#66817c}.empty{text-align:center;padding:95px 20px;color:#71838a}.empty-symbol{margin:0 auto 16px;border:1px dashed #a9c7bf;color:#669a8c;width:46px;height:46px;display:grid;place-items:center;font-size:25px;border-radius:9px}.empty h3{color:#35505a;font-size:15px}.empty p,.muted{font-size:13px;color:#8a999d}.task-grid{padding:26px;display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:12px}.task{background:white;border:1px solid #d9e8e4;border-radius:7px;padding:15px;display:flex;align-items:center;gap:12px;min-width:0}.task-number{background:#e4f2ed;color:#448b76;font-size:12px;padding:7px;border-radius:5px}.task div{min-width:0;flex:1}.task strong{font-size:14px;display:block;overflow-wrap:anywhere}.task small{display:block;color:#91a3a2;margin-top:5px}.remove{border:0;background:none;color:#91a3a2;cursor:pointer;font-size:20px}.remove:hover{color:#bc5555}.inspector{border-left:1px solid #e6eeed;min-width:0}.form-section{padding:20px 24px;border-bottom:1px solid #edf2f1}.form-section h3{margin:0 0 14px;font-size:14px}.form-section h3 span{color:#91a3a2;font-weight:400}.form-section label{display:block;color:#74878a;font-size:12px;margin:12px 0 6px}.full{width:100%;margin-top:10px}.form-section label+.full{margin-top:0}.edge{display:flex;justify-content:space-between;align-items:center;gap:6px;font-size:13px;overflow-wrap:anywhere}.edge b{color:#5d9b89}.unavailable{margin-top:24px;padding:30px;background:#fff;border:1px solid #e1e9e8}.unavailable h2{font-size:16px}.unavailable p{font-size:14px;color:#71838a;margin-bottom:20px}@media(max-width:900px){.editor{grid-template-columns:1fr}.inspector{border-left:0;border-top:1px solid #e6eeed}}@media(max-width:650px){.shell{display:block}.sidebar{width:auto;min-height:auto;padding:17px 15px 13px;border-right:0;border-bottom:1px solid #e5ece7}.brand{padding:0 4px}.pending{display:none}.primary-nav{display:flex;overflow:auto;margin:15px -3px 0;padding:2px 3px 5px;gap:6px}.primary-nav button{min-height:42px;white-space:nowrap;padding:0 11px}.nav-leading{gap:8px;font-size:12px}.nav-icon{width:16px;height:16px;flex-basis:16px}.content{padding:28px 18px}.workspace>header{min-height:62px;padding:10px 18px;gap:12px}.header-actions{gap:9px}.status{font-size:11px}.account-chip{gap:6px;padding-left:10px}.account-avatar{width:25px;height:25px}.logout-button{padding:7px 9px}.breadcrumbs{font-size:11px;gap:6px}.page-heading{align-items:start;flex-direction:column}}@media(max-width:420px){.workspace>header{align-items:flex-start;flex-direction:column}.header-actions{width:100%;justify-content:flex-end}.breadcrumbs{padding-top:2px}}
:root{--ui-navigation-size:clamp(13px,.68vw,16px);--ui-body-size:clamp(14px,calc(13px + .16vw),18px);--ui-control-size:clamp(14px,calc(12px + .17vw),17px);--el-font-size-base:var(--ui-control-size)}body{font-size:var(--ui-body-size)}
.nav-group{min-width:0}.nav-chevron{width:15px;height:15px;flex:0 0 15px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round;opacity:.72;transition:transform .18s ease}.nav-group>button.expanded .nav-chevron{transform:rotate(180deg)}.submenu{display:grid;gap:3px;margin:5px 0 7px 14px;padding-left:13px;border-left:1px solid #33496f}.submenu button{width:100%;min-height:39px;justify-content:flex-start;gap:10px;padding:0 10px;border-radius:8px;color:#aebddd;font-size:13px;font-weight:520}.submenu button:hover{background:#203557;color:#f7f9ff}.submenu button.active{background:#263f76;color:#f8faff;box-shadow:inset 2px 0 0 #8eacff}.submenu button .nav-icon{width:16px;height:16px;flex-basis:16px;opacity:.86}.submenu button.active .nav-icon{opacity:1}.nav-group>button:focus-visible,.submenu button:focus-visible{outline:3px solid rgba(154,183,255,.3);outline-offset:2px}
@media(min-width:651px){.sidebar{width:clamp(252px,13vw,320px)}.brand{gap:clamp(12px,.7vw,18px)}.brand .brand-cube{width:clamp(40px,2.45vw,56px);height:clamp(40px,2.45vw,56px);flex-basis:clamp(40px,2.45vw,56px)}.brand strong{font-size:clamp(15px,.9vw,20px)}.brand small{font-size:clamp(9px,.55vw,12px)}.primary-nav button{min-height:clamp(46px,2.15vw,56px)}.nav-leading{gap:clamp(12px,.72vw,18px);font-size:clamp(14px,.72vw,18px)}.nav-icon{width:clamp(18px,.95vw,22px);height:clamp(18px,.95vw,22px);flex-basis:clamp(18px,.95vw,22px)}.pending{font-size:clamp(10px,.52vw,13px);padding:clamp(5px,.28vw,7px) clamp(7px,.36vw,9px)}.submenu{margin-left:clamp(14px,.8vw,20px);padding-left:clamp(13px,.72vw,18px)}.submenu button{min-height:clamp(39px,1.9vw,50px);font-size:clamp(14px,.72vw,18px);gap:clamp(10px,.6vw,15px)}.submenu button .nav-icon{width:clamp(16px,.83vw,20px);height:clamp(16px,.83vw,20px);flex-basis:clamp(16px,.83vw,20px)}.workspace>header{min-height:clamp(66px,3.8vw,90px);padding:0 clamp(28px,1.85vw,48px);font-size:clamp(13px,.67vw,16px)}.status,.account-chip,.logout-button{font-size:clamp(13px,.67vw,16px)}.account-avatar{width:clamp(27px,1.5vw,36px);height:clamp(27px,1.5vw,36px);font-size:clamp(11px,.58vw,14px)}.logout-button{padding:clamp(8px,.46vw,12px) clamp(11px,.65vw,17px)}.logout-button svg{width:clamp(15px,.78vw,19px);height:clamp(15px,.78vw,19px)}.content{max-width:min(1800px,calc(100vw - 360px));padding:clamp(36px,2vw,54px) clamp(32px,2vw,50px)}.page-heading h1,.content>h1{font-size:clamp(28px,1.8vw,40px)}.kicker,.subheading{font-size:clamp(14px,.76vw,18px)}.el-button,.el-input__inner,.el-select__placeholder,.el-table,.el-table .cell,.el-tabs__item,.el-pagination,.el-form-item__label,.el-dialog__title{font-size:var(--ui-control-size)}.el-button{min-height:clamp(32px,1.9vw,42px)}.toolbar{gap:clamp(12px,.8vw,20px);margin:clamp(16px,1vw,24px) 0}.toolbar .el-input{width:min(420px,100%)}}
@media(min-width:651px) and (max-width:1100px){.sidebar{width:220px}.content{max-width:100%;padding:30px 24px}.workspace>header{padding:0 20px}}
@media(max-width:650px){:root{--ui-navigation-size:clamp(12px,3.3vw,14px)}.nav-leading{font-size:var(--ui-navigation-size)}.submenu button{font-size:var(--ui-navigation-size)}.content{padding:clamp(24px,5vw,32px) 18px}}
.page-actions{display:flex;justify-content:flex-end;align-items:center;margin:0 0 10px}
.shell .content .el-tab-pane,.shell .content section > div:has(> .toolbar + .el-table),.shell .content .list-panel,.shell .content section .pane{min-height:min(620px,calc(100vh - 200px));padding:14px;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:4px;box-shadow:var(--el-box-shadow-lighter)}
.shell .content section .toolbar{display:flex;align-items:center;justify-content:flex-start;gap:10px;margin:0 0 8px;padding:0;border:0;border-radius:0;background:transparent;box-shadow:none}
.shell .content section .toolbar.list-query-toolbar{justify-content:space-between;flex-wrap:wrap}
.shell .content section .toolbar.list-query-toolbar .list-query-controls,.shell .content section .toolbar.list-query-toolbar .list-query-actions{display:flex;align-items:center;gap:10px;min-width:0}
.shell .content section .toolbar.list-query-toolbar .list-query-controls{flex:1;flex-wrap:wrap}
.shell .content section .toolbar.list-query-toolbar .list-query-actions{margin-left:auto;flex-wrap:wrap;justify-content:flex-end}
.shell .content section .toolbar.list-query-toolbar .list-query-controls .el-input{flex:0 1 260px;width:min(260px,100%)}
.shell .content section .toolbar:has(.el-input),.shell .content section .pane > .row{min-height:52px;padding:10px 12px;margin:0 0 10px;border:1px solid var(--el-border-color-lighter);border-radius:4px;background:#fff}
.shell .content section .toolbar .el-input{width:min(260px,100%)}
.shell .content .el-table{width:100%;--el-table-header-bg-color:var(--el-fill-color-light);--el-table-row-hover-bg-color:#eaf3ff;border:0;border-radius:0;overflow:visible}
.shell .content .el-table th.el-table__cell{height:44px;color:var(--el-text-color-secondary);font-weight:600;text-align:center}
.shell .content .el-table td.el-table__cell{height:42px}
.shell .content .el-table th .cell,.shell .content .el-table td .cell,.el-dialog .el-table th .cell,.el-dialog .el-table td .cell{text-align:center}
.shell .content .el-pagination,.shell .content section .pagination{display:flex;justify-content:flex-end;align-items:center;margin:16px 0 0;padding:0;border:0;border-radius:0;background:transparent}
.shell .content section .pane{margin:0;padding:14px;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:4px;box-shadow:var(--el-box-shadow-lighter)}
.shell .content section .pane > .el-alert{margin:10px 0 14px}
.shell .content section .pane > .row{margin:0 0 10px}
.shell .content .el-tabs__header{margin-bottom:12px}
.shell .content .el-tabs__nav-wrap::after{background-color:var(--el-border-color-light)}
@media(max-width:700px){.shell .content section .toolbar{flex-wrap:wrap}.shell .content .el-tab-pane,.shell .content section > div:has(> .toolbar + .el-table),.shell .content .list-panel,.shell .content section .pane{min-height:0;padding:10px}.shell .content .el-pagination,.shell .content section .pagination{justify-content:flex-start;overflow-x:auto}}
@media(max-width:700px){.shell .content section .toolbar.list-query-toolbar .list-query-controls{flex:1 1 100%}.shell .content section .toolbar.list-query-toolbar .list-query-actions{margin-left:auto}}
.shell .workspace > .content{width:100%;max-width:none;margin:0;padding:10px 12px}
@media(max-width:650px){.shell .workspace > .content{padding:8px}}
:root{--ui-text-primary:#24344b;--ui-text-body:#40536a;--ui-text-secondary:#66778c;--ui-text-muted:#8b98a8;--el-text-color-primary:var(--ui-text-primary);--el-text-color-regular:var(--ui-text-body);--el-text-color-secondary:var(--ui-text-secondary);--el-text-color-placeholder:var(--ui-text-muted);--el-text-color-disabled:#b4bec9}
body{color:var(--ui-text-primary)}
.workspace>header{color:var(--ui-text-secondary)}
.breadcrumbs strong,.account-chip{color:var(--ui-text-primary)}
.status{color:var(--ui-text-secondary)}
.account-avatar{color:#426f9a}
.shell .content{color:var(--ui-text-body)}
.shell .content h1,.shell .content h2,.shell .content h3,.shell .content strong{color:var(--ui-text-primary)}
.shell .content .kicker,.shell .content .subheading,.shell .content .toolbar>span,.shell .content .toolbar small,.shell .content .list-caption,.shell .content .status-text,.shell .content .count,.shell .content .muted,.shell .content .empty p{color:var(--ui-text-secondary)}
.shell .content .el-table{--el-table-text-color:var(--ui-text-body);--el-table-header-text-color:var(--ui-text-secondary)}
@media(min-width:651px){.sidebar{width:clamp(220px,11vw,280px)}.primary-nav{width:calc(100% + 32px);margin-left:-16px;margin-right:-16px}.primary-nav .nav-group,.primary-nav .nav-group>button{width:100%}.primary-nav .nav-leading{flex:1;font-size:var(--ui-navigation-size)}.primary-nav .nav-chevron{margin-left:auto}.submenu button{font-size:clamp(12px,.62vw,14px)}}
.shell .content .el-table,.shell .content .el-table .cell,.shell .content .el-table .el-button{font-size:var(--ui-navigation-size)}
.shell .content .toolbar{--ui-toolbar-height:calc(var(--ui-navigation-size) + 19px)}
.shell .content .toolbar .el-button,.shell .content .toolbar .el-input__inner,.shell .content .toolbar .el-select__wrapper,.shell .content .toolbar .el-select__placeholder,.shell .content .toolbar .el-select__selected-item{font-size:var(--ui-navigation-size)}
.shell .content .toolbar .el-button{height:var(--ui-toolbar-height);min-height:0;padding:0 calc(var(--ui-navigation-size) * .9)}
.el-button.refresh-button{width:clamp(32px,1.9vw,42px);height:clamp(32px,1.9vw,42px);min-height:0;padding:0;border-radius:50%;flex:none}
.shell .content .toolbar .el-button.refresh-button{width:var(--ui-toolbar-height);height:var(--ui-toolbar-height);padding:0}
.shell .content .toolbar .el-input{--el-input-height:var(--ui-toolbar-height)}
.shell .content .toolbar .el-select__wrapper{min-height:var(--ui-toolbar-height)}
.shell{height:100vh;height:100dvh;min-height:0;overflow:hidden}
.sidebar{height:100%;min-height:0;overflow:hidden}
.brand{flex:0 0 auto}
.primary-nav{flex:1 1 auto;min-height:0;align-content:start;overflow-x:hidden;overflow-y:auto;overscroll-behavior:contain;scrollbar-width:thin;scrollbar-color:rgba(171,191,228,.35) transparent}
.workspace{height:100%;min-height:0;overflow:hidden;display:flex;flex-direction:column}
.workspace>header{flex:0 0 auto;position:relative;z-index:2}
.header-leading{display:flex;align-items:center;gap:12px;min-width:0}
.mobile-nav-trigger{display:none;align-items:center;justify-content:center;width:38px;height:38px;flex:0 0 38px;border:1px solid #dbe4ef;border-radius:9px;background:#fff;color:#355373;cursor:pointer}
.mobile-nav-trigger svg{width:19px;height:19px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round}
.mobile-nav-trigger:focus-visible{outline:3px solid rgba(55,128,211,.24);outline-offset:2px}
.shell .workspace>.content{flex:1 1 0;min-width:0;min-height:0;overflow:auto;overscroll-behavior:contain}
@media(max-width:760px){
  .shell{display:flex;flex-direction:column}
  .sidebar{position:fixed;z-index:51;inset:0 auto 0 0;width:min(300px,86vw);height:100vh;height:100dvh;min-height:0;padding:22px 16px 16px;border-right:1px solid #2b4165;border-bottom:0;transform:translateX(-102%);transition:transform .2s ease,box-shadow .2s ease}
  .sidebar.mobile-open{transform:translateX(0);box-shadow:14px 0 36px rgba(7,19,42,.24)}
  .brand{padding:0 8px}
  .pending{display:block}
  .primary-nav{display:grid;width:calc(100% + 32px);margin:24px -16px 8px;padding:4px 16px;overflow-x:hidden;overflow-y:auto;gap:5px}
  .primary-nav button{min-height:46px;white-space:normal}
  .submenu{display:grid}
  .nav-backdrop{position:fixed;z-index:50;inset:0;border:0;background:rgba(12,28,51,.44);cursor:pointer}
  .workspace{width:100%;height:auto;flex:1 1 0}
  .workspace>header{min-height:62px;padding:10px 16px;gap:12px}
  .header-leading{flex:1 1 auto}
  .mobile-nav-trigger{display:inline-flex}
  .breadcrumbs{min-width:0;flex-wrap:wrap}
  .header-actions{gap:9px}
}
@media(max-width:420px){.workspace>header{align-items:flex-start;flex-direction:column}.header-leading{width:100%;min-width:0}.header-actions{width:100%;justify-content:flex-end;flex:0 0 auto}.breadcrumbs{padding-top:0}}
@media(prefers-reduced-motion:reduce){.sidebar{transition:none}}
</style>
