<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { login, authorizedRequest } from './auth.js'
import DataSources from './DataSources.vue'
import TaskStudio from './TaskStudio.vue'
import Organization from './Organization.vue'
import DataExplorer from './DataExplorer.vue'
import Reports from './Reports.vue'
import Services from './Services.vue'
import Realtime from './Realtime.vue'

const status = ref('正在检查')
const user = ref(null)
const sessionLoading = ref(true)
const username = ref('')
const password = ref('')
const rememberUsername = ref(false)
const loginError = ref('')
const loggingIn = ref(false)
async function loadSession() {
  const response = await fetch('/api/session', { credentials: 'same-origin' })
  user.value = response.ok ? (await response.json()).username : null
  if (response.ok) await loadNavigation()
  else navigation.value = fallbackMenus
}
async function signIn() {
  loggingIn.value = true; loginError.value = ''
  try {
    try {
      if (rememberUsername.value) localStorage.setItem('governance-platform-username', username.value)
      else localStorage.removeItem('governance-platform-username')
    } catch { /* Storage may be unavailable in private browsing. */ }
    await login(username.value, password.value); password.value = ''; await loadSession()
  }
  catch (error) { loginError.value = error.message }
  finally { loggingIn.value = false }
}
function showForgotPassword() { void ElMessageBox.alert('请联系平台管理员重置账号密码。', '找回密码', { confirmButtonText: '知道了' }) }
async function signOut() {
  if (!(await canLeave())) return
  try { const response = await authorizedRequest('/api/logout', { method: 'POST' }); if (!response.ok && response.status !== 401) throw new Error(); user.value = null }
  catch { loginError.value = '退出失败，请检查服务连接' }
}

const section = ref('tasks')
const mobileNavigationOpen = ref(false)
const iconPaths = {
  grid: 'M4 4h7v7H4z M13 4h7v5h-7z M13 11h7v9h-7z M4 13h7v7H4z',
  database: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3Zm0 0v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6m-16 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6',
  workflow: 'M6 4v4a4 4 0 0 0 4 4h4a4 4 0 0 1 4 4v4m-12 0v-3m12-13v3',
  pulse: 'M3 12h4l3-7 4 14 3-7h4',
  settings: 'M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4m0-12.8L17 7M7 17l-1.4 1.4M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z',
  box: 'M12 3 20 7.5v9L12 21l-8-4.5v-9L12 3Zm-8 4.5 8 4.5 8-4.5m-8 4.5V21',
  search: 'M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13Zm5 11 4.5 4.5',
  chart: 'M4 20h16M7 16v-5m5 5V5m5 11v-8',
  system: 'M4 7h9m4 0h3M4 17h3m4 0h9m-7-2v4m-4-14v4m8-4v4',
  building: 'M3 21h18M5 21V7l7-4 7 4v14M9 10h.01M15 10h.01M9 14h.01M15 14h.01M10 21v-3h4v3',
  roles: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4 21a8 8 0 0 1 16 0m-3-12h4m-2-2v4',
  users: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4 21a8 8 0 0 1 16 0',
  menu: 'M4 6h16M4 12h16M4 18h16M8 6v.01M8 12v.01M8 18v.01',
  audit: 'M12 8v4l3 2m6-2a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
}
const fallbackMenus = [
  { id: 1, parentId: null, name: '平台概览', menuType: 'M', component: 'overview', icon: 'grid' },
  { id: 2, parentId: null, name: '数据资产', menuType: 'M', component: 'assets', icon: 'database' },
  { id: 3, parentId: null, name: '任务与工作流', menuType: 'M', component: 'tasks', icon: 'workflow' },
  { id: 4, parentId: null, name: '实时同步', menuType: 'M', component: 'realtime', icon: 'pulse' },
  { id: 5, parentId: null, name: '运行运维', menuType: 'M', component: 'operations', icon: 'settings' },
  { id: 6, parentId: null, name: '数据服务', menuType: 'M', component: 'services', icon: 'box' },
  { id: 7, parentId: null, name: '数据查询', menuType: 'M', component: 'query', icon: 'search' },
  { id: 8, parentId: null, name: '数据报表', menuType: 'M', component: 'reports', icon: 'chart' },
  { id: 9, parentId: null, name: '系统管理', menuType: 'D', component: 'system', icon: 'system' },
  { id: 91, parentId: 9, name: '部门管理', menuType: 'M', component: 'system/departments', icon: 'building' },
  { id: 92, parentId: 9, name: '角色管理', menuType: 'M', component: 'system/roles', icon: 'roles' },
  { id: 93, parentId: 9, name: '用户管理', menuType: 'M', component: 'system/users', icon: 'users' },
  { id: 94, parentId: 9, name: '菜单管理', menuType: 'M', component: 'system/menus', icon: 'menu' },
  { id: 95, parentId: 9, name: '审计记录', menuType: 'M', component: 'system/audit', icon: 'audit' },
]
const navigation = ref(fallbackMenus)
const menu = computed(() => {
  const build = parentId => navigation.value.filter(item => item.parentId === parentId).map(item => {
    const children = build(item.id)
    const normalized = { ...item, label: item.name, icon: iconPaths[item.icon] || iconPaths.menu }
    if (children.length) normalized.children = children
    return normalized
  })
  return build(null)
})
const systemPages = computed(() => menu.value.find(item => item.component === 'system')?.children || [])
const systemPage = ref('departments')
const systemExpanded = ref(false)
const sectionItem = computed(() => menu.value.find(item => item.component === section.value))
const sectionLabel = computed(() => sectionItem.value?.label ?? '工作空间')
const systemPageLabel = computed(() => systemPages.value.find(item => item.component === `system/${systemPage.value}`)?.label ?? '部门管理')
const pendingMenus = new Set(['overview', 'operations'])
async function loadNavigation() {
  try {
    const response = await fetch('/api/navigation', { credentials: 'same-origin' })
    if (!response.ok) return
    const rows = await response.json()
    if (!Array.isArray(rows)) return
    navigation.value = rows
    if (!rows.some(item => item.component === section.value)) {
      const first = rows.find(item => item.parentId == null)
      if (first) section.value = first.component === 'system' ? 'system' : first.component
    }
    const firstSystemPage = rows.find(item => item.parentId != null && item.component?.startsWith('system/'))
    if (firstSystemPage && !rows.some(item => item.component === `system/${systemPage.value}`)) systemPage.value = firstSystemPage.component.slice('system/'.length)
  } catch { /* Keep the built-in menu if navigation cannot be loaded. */ }
}
const taskStudio = ref(null)
async function canLeave() {
  if (section.value !== 'tasks' || !taskStudio.value?.hasUnsavedWorkflow()) return true
  try { await ElMessageBox.confirm('当前工作流编排尚未保存，离开后修改将丢失。', '确认离开', { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' }); return true } catch { return false }
}
async function navigate(item) {
  if (item.children) {
    if (section.value !== item.component) {
      if (!(await canLeave())) return
      section.value = item.component
      const initialPage = item.children.find(child => child.component?.startsWith('system/'))
      if (initialPage) systemPage.value = initialPage.component.slice('system/'.length)
      systemExpanded.value = true
    } else systemExpanded.value = !systemExpanded.value
    return
  }
  const target = typeof item === 'string' ? item : item.component
  if (target !== section.value && await canLeave()) { section.value = target; systemExpanded.value = false; mobileNavigationOpen.value = false }
  else if (target === section.value) mobileNavigationOpen.value = false
}
async function navigateSystemPage(item) {
  if (section.value !== 'system' && !(await canLeave())) return
  section.value = 'system'
  systemPage.value = item.component?.startsWith('system/') ? item.component.slice('system/'.length) : item.id
  systemExpanded.value = true
  mobileNavigationOpen.value = false
}
onMounted(async () => {
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
          <button type="button" :class="{ active: section === item.component, 'has-children': !!item.children, expanded: !!item.children && systemExpanded }" :aria-current="section === item.component ? 'page' : undefined" :aria-expanded="item.children ? systemExpanded : undefined" @click="navigate(item)">
            <span class="nav-leading"><svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true"><path :d="item.icon" /></svg><span>{{ item.label }}</span></span>
            <span v-if="pendingMenus.has(item.component)" class="pending">待接入</span>
            <svg v-else-if="item.children" class="nav-chevron" viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5" /></svg>
          </button>
          <div v-if="item.children && systemExpanded" class="submenu" aria-label="系统管理子菜单">
            <button v-for="child in item.children" :key="child.component" type="button" :class="{ active: section === item.component && systemPage === child.component?.slice('system/'.length) }" :aria-current="section === item.component && systemPage === child.component?.slice('system/'.length) ? 'page' : undefined" @click.stop="navigateSystemPage(child)">
              <svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true"><path :d="child.icon" /></svg><span>{{ child.label }}</span>
            </button>
          </div>
        </div>
      </nav>
      <div class="sidebar-foot"><span class="workspace-dot"></span><span>单节点工作空间</span><small>LOCAL</small></div>
    </aside>
    <section class="workspace">
      <header>
        <div class="header-leading">
          <button class="mobile-nav-trigger" type="button" aria-label="打开导航菜单" :aria-expanded="mobileNavigationOpen" @click="mobileNavigationOpen = !mobileNavigationOpen"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 5h14M3 10h14M3 15h14" /></svg></button>
          <div class="breadcrumbs"><span>工作空间</span><span class="slash">/</span><strong>{{ sectionLabel }}</strong><template v-if="section === 'system'"><span class="slash">/</span><strong>{{ systemPageLabel }}</strong></template></div>
        </div>
        <div class="header-actions">
          <span class="status"><i :class="{ offline: status !== '服务正常' }"></i>{{ status }}</span>
          <span class="account-chip"><span class="account-avatar">{{ user?.slice(0, 1)?.toUpperCase() }}</span><span>{{ user }}</span></span>
          <button class="logout-button" type="button" @click="signOut"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M8 4H4.8A1.8 1.8 0 0 0 3 5.8v8.4A1.8 1.8 0 0 0 4.8 16H8m3-9 4 3-4 3m4-3H7" /></svg><span>退出</span></button>
        </div>
      </header>
      <div v-if="section === 'assets'" class="content"><DataSources /></div>
      <div v-else-if="section === 'tasks'" class="content"><TaskStudio ref="taskStudio" /></div>
      <div v-else-if="section === 'system'" class="content"><Organization :view="systemPage" @navigation-updated="loadNavigation" /></div>
      <div v-else-if="section === 'query'" class="content"><DataExplorer /></div>
      <div v-else-if="section === 'reports'" class="content"><Reports /></div>
      <div v-else-if="section === 'services'" class="content"><Services /></div>
      <div v-else-if="section === 'realtime'" class="content"><Realtime /></div>
      <div v-else class="content"><div class="unavailable"><h2>功能尚未接入</h2><p>该模块仍在开发，可前往任务与工作流查看已接入的功能。</p><el-button @click="navigate('tasks')">前往任务与工作流</el-button></div></div>
    </section>
  </main>
</template>
<style>
/* User-provided data governance background */
.login-screen{position:relative;isolation:isolate;overflow:hidden;min-height:100vh;min-height:100svh;display:flex;align-items:center;justify-content:flex-end;padding:32px clamp(24px,3vw,64px);background:#123f78;color:#173654}
.login-screen::before,.login-screen::after{content:"";position:absolute;inset:0;pointer-events:none}
.login-screen::before{z-index:-2;inset:-1.3%;background:url("/images/login-background-previous.png") center center/cover no-repeat;animation:login-backdrop-drift 48s ease-in-out infinite alternate;will-change:transform}
.login-screen::after{z-index:-1;background:linear-gradient(90deg,transparent 55%,rgba(237,248,252,.08) 100%)}
.login-card{width:min(35vw,690px);min-width:440px;margin-left:auto;padding:38px clamp(30px,3vw,52px) 24px;display:flex;flex-direction:column;justify-content:flex-start;gap:17px;border:1px solid rgba(255,255,255,.94);border-radius:23px;background:rgba(255,255,255,.97);box-shadow:0 22px 58px rgba(11,45,88,.22),0 3px 10px rgba(20,73,126,.08);backdrop-filter:blur(12px);animation:login-card-in .55s cubic-bezier(.2,.72,.2,1) both}
.card-brand{display:flex;align-items:center;gap:12px;margin-bottom:4px;color:#122f4c}.card-brand .brand-cube{display:block;width:45px;height:45px;flex:0 0 45px}.card-brand strong{font-size:23px;font-weight:700;letter-spacing:-.02em}.login-card h2{margin:20px 0 0;color:#102b48;font-size:clamp(30px,2.6vw,39px);font-weight:720;line-height:1.2;letter-spacing:.02em}.card-subtitle{margin:0 0 18px;color:#677b91;font-size:20px;line-height:1.4}
.login-card .el-input{width:100%}.login-card .el-input__wrapper{min-height:56px;padding:0 17px;border:1px solid #d9e2eb;border-radius:10px;background:rgba(255,255,255,.96);box-shadow:0 1px 3px rgba(34,72,110,.04),0 0 0 1px rgba(221,231,239,.35) inset;transition:border-color .18s ease,box-shadow .18s ease}.login-card .el-input__wrapper:hover{border-color:#a9c9e8;box-shadow:0 0 0 3px rgba(48,134,227,.08)}.login-card .el-input__wrapper.is-focus{border-color:#4594ed;box-shadow:0 0 0 3px rgba(48,134,227,.12)}.login-card .el-input__inner{color:#1d3348;font-size:16px}.login-card .el-input__inner::placeholder{color:#8493a3}.field-icon{display:block;width:21px;height:21px;fill:none;stroke:#223b50;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}.login-card .el-input__prefix{margin-right:10px}
.login-options{display:flex;align-items:center;justify-content:space-between;margin:-2px 0 1px}.login-options .el-checkbox__label{color:#34485d;font-size:14px}.login-options .el-checkbox__inner{width:16px;height:16px;border-color:#9eb2c3}.forgot-link{padding:4px 0;border:0;background:transparent;color:#1671eb;font:inherit;font-size:14px;font-weight:600;cursor:pointer}.forgot-link:hover{text-decoration:underline}.login-card .el-button--primary{width:100%;height:56px;margin-top:5px;border:0;border-radius:10px;background:linear-gradient(105deg,#0769f5 0%,#3589fb 100%);box-shadow:0 7px 16px rgba(17,112,244,.22);font-size:18px;font-weight:650;letter-spacing:.08em;transition:filter .18s ease,transform .18s ease}.login-card .el-button--primary:hover{filter:brightness(1.04);transform:translateY(-1px)}.login-security{display:flex;align-items:center;justify-content:center;gap:8px;margin-top:12px;color:#72879a;font-size:11px}.login-security svg{display:block;width:15px;height:15px;flex:0 0 15px;fill:none;stroke:#368b9d;stroke-width:1.3;stroke-linecap:round;stroke-linejoin:round}
.login-loading{justify-content:center}.login-loading-card{padding:18px 26px;border:1px solid rgba(255,255,255,.88);border-radius:12px;background:rgba(255,255,255,.94);color:#536c80;font-size:14px;box-shadow:0 12px 36px rgba(13,58,111,.18)}
@keyframes login-backdrop-drift{0%{transform:scale(1)}100%{transform:scale(1.025)}}@keyframes login-card-in{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}
@media(max-width:1120px){.login-card{width:min(40vw,520px);min-width:410px;padding-right:34px;padding-left:34px}}
@media(max-width:820px){.login-screen{justify-content:center;padding:22px}.login-screen::before{background-size:auto 124%;background-position:80% 56%}.login-card{width:min(100%,480px);min-width:0;min-height:0;margin:auto;padding:35px 34px;border-radius:20px}.login-card h2{font-size:32px}.card-subtitle{font-size:18px}}
@media(max-width:480px){.login-screen{padding:16px}.login-screen::before{background-position:84% 58%}.login-card{padding:29px 24px;border-radius:18px}.login-card h2{font-size:29px}.card-subtitle{font-size:17px}.login-card .el-input__wrapper,.login-card .el-button--primary{min-height:52px;height:52px}}
@media(prefers-reduced-motion:reduce){.login-screen::before,.login-card{animation:none}.login-card .el-button--primary,.login-card .el-input__wrapper{transition:none}}
*{box-sizing:border-box}body{margin:0;background:#f5f7fb;color:#243d3a;font-family:"PingFang SC","Microsoft YaHei",sans-serif}button{font:inherit}.shell{min-height:100vh;display:flex;background:#f5f7fb}.sidebar{width:252px;background:linear-gradient(180deg,#172847 0%,#14223f 100%);color:#dce6ff;padding:26px 16px 18px;border-right:1px solid #243656;flex-shrink:0;display:flex;flex-direction:column}.brand{display:flex;gap:12px;align-items:center;padding:0 8px}.brand .brand-cube{display:block;width:40px;height:40px;flex:0 0 40px}.brand strong{display:block;color:#f5f8ff;font-size:15px;font-weight:700;letter-spacing:.02em}.brand small{display:block;color:#9aadd3;font-size:9px;letter-spacing:1.15px;font-weight:650;margin-top:5px}.primary-nav{display:grid;gap:5px;margin-top:42px}.primary-nav button{position:relative;min-height:46px;border:0;background:transparent;color:#c4d2ef;text-align:left;padding:0 11px;border-radius:10px;cursor:pointer;display:flex;align-items:center;justify-content:space-between;transition:background .18s ease,color .18s ease,transform .18s ease}.nav-leading{display:flex;align-items:center;gap:12px;min-width:0;font-size:13px;font-weight:560}.nav-icon{width:18px;height:18px;flex:0 0 18px;fill:none;stroke:currentColor;stroke-width:1.65;stroke-linecap:round;stroke-linejoin:round;opacity:.83}.primary-nav button:hover{background:#203557;color:#f6f8ff}.primary-nav button:active{transform:scale(.985)}.primary-nav button.active{background:#2a467f;color:#f7f9ff;box-shadow:inset 3px 0 0 #9ab7ff,0 4px 12px rgba(4,13,34,.12)}.primary-nav button.active .nav-icon{opacity:1}.pending{flex:0 0 auto;font-size:10px;line-height:1;padding:5px 7px;border-radius:6px;background:rgba(255,255,255,.09);color:#b4c2e3;letter-spacing:.01em}.sidebar-foot{margin:auto 4px 0;padding:11px 12px;border:1px solid #2a3b5e;border-radius:11px;background:rgba(255,255,255,.045);display:flex;align-items:center;gap:8px;color:#a9b9d9;font-size:11px}.workspace-dot{width:7px;height:7px;flex:0 0 7px;border-radius:50%;background:#65a78a;box-shadow:0 0 0 3px #e8f4ed}.sidebar-foot small{margin-left:auto;color:#879bc4;font-size:9px;font-weight:700;letter-spacing:.1em}.workspace{min-width:0;flex:1;background:#f5f7fb}.workspace>header{min-height:66px;background:rgba(255,255,255,.92);border-bottom:1px solid #e5ece8;padding:0 34px;display:flex;align-items:center;justify-content:space-between;color:#81918b;font-size:12px}.breadcrumbs{display:flex;align-items:center;gap:9px}.breadcrumbs strong{color:#405a52;font-weight:650}.slash{color:#b6c2bc}.header-actions{display:flex;align-items:center;gap:18px}.status{display:flex;align-items:center;gap:7px;color:#71877e;font-size:12px}.status i{height:7px;width:7px;border-radius:50%;background:#58a582;box-shadow:0 0 0 3px #eaf4ed}.status i.offline{background:#d19857;box-shadow:0 0 0 3px #f8f0e4}.account-chip{display:flex;align-items:center;gap:8px;padding-left:17px;border-left:1px solid #e8eeea;color:#455e55;font-size:12px;font-weight:600}.account-avatar{width:27px;height:27px;border-radius:9px;display:grid;place-items:center;background:#eaf2ed;color:#3d7968;font-size:11px;font-weight:750}.logout-button{display:inline-flex;align-items:center;justify-content:center;gap:7px;padding:8px 11px;border:1px solid #dfe8e2;border-radius:9px;background:#fff;color:#697c74;font-size:12px;font-weight:600;cursor:pointer;transition:background .18s ease,border-color .18s ease,color .18s ease,transform .18s ease}.logout-button svg{width:15px;height:15px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}.logout-button:hover{background:#fff4f1;border-color:#f0d5cf;color:#a65c50}.logout-button:active{transform:translateY(1px)}.primary-nav button:focus-visible,.logout-button:focus-visible{outline:3px solid rgba(75,143,119,.24);outline-offset:2px}.content{max-width:1500px;margin:auto;padding:40px 36px}.page-heading{display:flex;justify-content:space-between;align-items:end;gap:20px;margin-bottom:24px}.kicker{font-size:13px;color:#63867e;margin:0 0 8px}.page-heading h1,.content>h1{font-size:28px;margin:0;font-weight:650}.subheading{margin:9px 0 0;color:#71838a;font-size:14px}.feedback{margin-top:12px}.editor{display:grid;grid-template-columns:minmax(0,1fr) 310px;border:1px solid #dae5e5;background:white;margin-top:22px;min-height:510px}.diagram{background:#fbfdfc;min-width:0}.panel-head{padding:23px 25px;border-bottom:1px solid #e6eeed;display:flex;justify-content:space-between;gap:12px}.panel-head h2{font-size:16px;margin:0}.panel-head p{font-size:12px;color:#8a999d;margin:7px 0 0}.count{font-size:12px;color:#66817c}.empty{text-align:center;padding:95px 20px;color:#71838a}.empty-symbol{margin:0 auto 16px;border:1px dashed #a9c7bf;color:#669a8c;width:46px;height:46px;display:grid;place-items:center;font-size:25px;border-radius:9px}.empty h3{color:#35505a;font-size:15px}.empty p,.muted{font-size:13px;color:#8a999d}.task-grid{padding:26px;display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:12px}.task{background:white;border:1px solid #d9e8e4;border-radius:7px;padding:15px;display:flex;align-items:center;gap:12px;min-width:0}.task-number{background:#e4f2ed;color:#448b76;font-size:12px;padding:7px;border-radius:5px}.task div{min-width:0;flex:1}.task strong{font-size:14px;display:block;overflow-wrap:anywhere}.task small{display:block;color:#91a3a2;margin-top:5px}.remove{border:0;background:none;color:#91a3a2;cursor:pointer;font-size:20px}.remove:hover{color:#bc5555}.inspector{border-left:1px solid #e6eeed;min-width:0}.form-section{padding:20px 24px;border-bottom:1px solid #edf2f1}.form-section h3{margin:0 0 14px;font-size:14px}.form-section h3 span{color:#91a3a2;font-weight:400}.form-section label{display:block;color:#74878a;font-size:12px;margin:12px 0 6px}.full{width:100%;margin-top:10px}.form-section label+.full{margin-top:0}.edge{display:flex;justify-content:space-between;align-items:center;gap:6px;font-size:13px;overflow-wrap:anywhere}.edge b{color:#5d9b89}.unavailable{margin-top:24px;padding:30px;background:#fff;border:1px solid #e1e9e8}.unavailable h2{font-size:16px}.unavailable p{font-size:14px;color:#71838a;margin-bottom:20px}@media(max-width:900px){.editor{grid-template-columns:1fr}.inspector{border-left:0;border-top:1px solid #e6eeed}}@media(max-width:650px){.shell{display:block}.sidebar{width:auto;min-height:auto;padding:17px 15px 13px;border-right:0;border-bottom:1px solid #e5ece7}.brand{padding:0 4px}.sidebar-foot,.pending{display:none}.primary-nav{display:flex;overflow:auto;margin:15px -3px 0;padding:2px 3px 5px;gap:6px}.primary-nav button{min-height:42px;white-space:nowrap;padding:0 11px}.nav-leading{gap:8px;font-size:12px}.nav-icon{width:16px;height:16px;flex-basis:16px}.content{padding:28px 18px}.workspace>header{min-height:62px;padding:10px 18px;gap:12px}.header-actions{gap:9px}.status{font-size:11px}.account-chip{gap:6px;padding-left:10px}.account-avatar{width:25px;height:25px}.logout-button{padding:7px 9px}.breadcrumbs{font-size:11px;gap:6px}.page-heading{align-items:start;flex-direction:column}}@media(max-width:420px){.workspace>header{align-items:flex-start;flex-direction:column}.header-actions{width:100%;justify-content:flex-end}.breadcrumbs{padding-top:2px}}
:root{--ui-navigation-size:clamp(13px,.68vw,16px);--ui-body-size:clamp(14px,calc(13px + .16vw),18px);--ui-control-size:clamp(14px,calc(12px + .17vw),17px);--el-font-size-base:var(--ui-control-size)}body{font-size:var(--ui-body-size)}
.nav-group{min-width:0}.nav-chevron{width:15px;height:15px;flex:0 0 15px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round;opacity:.72;transition:transform .18s ease}.nav-group>button.expanded .nav-chevron{transform:rotate(180deg)}.submenu{display:grid;gap:3px;margin:5px 0 7px 14px;padding-left:13px;border-left:1px solid #33496f}.submenu button{width:100%;min-height:39px;justify-content:flex-start;gap:10px;padding:0 10px;border-radius:8px;color:#aebddd;font-size:13px;font-weight:520}.submenu button:hover{background:#203557;color:#f7f9ff}.submenu button.active{background:#263f76;color:#f8faff;box-shadow:inset 2px 0 0 #8eacff}.submenu button .nav-icon{width:16px;height:16px;flex-basis:16px;opacity:.86}.submenu button.active .nav-icon{opacity:1}.nav-group>button:focus-visible,.submenu button:focus-visible{outline:3px solid rgba(154,183,255,.3);outline-offset:2px}
@media(min-width:651px){.sidebar{width:clamp(252px,13vw,320px)}.brand{gap:clamp(12px,.7vw,18px)}.brand .brand-cube{width:clamp(40px,2.45vw,56px);height:clamp(40px,2.45vw,56px);flex-basis:clamp(40px,2.45vw,56px)}.brand strong{font-size:clamp(15px,.9vw,20px)}.brand small{font-size:clamp(9px,.55vw,12px)}.primary-nav button{min-height:clamp(46px,2.15vw,56px)}.nav-leading{gap:clamp(12px,.72vw,18px);font-size:clamp(14px,.72vw,18px)}.nav-icon{width:clamp(18px,.95vw,22px);height:clamp(18px,.95vw,22px);flex-basis:clamp(18px,.95vw,22px)}.pending{font-size:clamp(10px,.52vw,13px);padding:clamp(5px,.28vw,7px) clamp(7px,.36vw,9px)}.submenu{margin-left:clamp(14px,.8vw,20px);padding-left:clamp(13px,.72vw,18px)}.submenu button{min-height:clamp(39px,1.9vw,50px);font-size:clamp(14px,.72vw,18px);gap:clamp(10px,.6vw,15px)}.submenu button .nav-icon{width:clamp(16px,.83vw,20px);height:clamp(16px,.83vw,20px);flex-basis:clamp(16px,.83vw,20px)}.sidebar-foot{font-size:clamp(12px,.6vw,15px);padding:clamp(11px,.62vw,16px)}.workspace>header{min-height:clamp(66px,3.8vw,90px);padding:0 clamp(28px,1.85vw,48px);font-size:clamp(13px,.67vw,16px)}.status,.account-chip,.logout-button{font-size:clamp(13px,.67vw,16px)}.account-avatar{width:clamp(27px,1.5vw,36px);height:clamp(27px,1.5vw,36px);font-size:clamp(11px,.58vw,14px)}.logout-button{padding:clamp(8px,.46vw,12px) clamp(11px,.65vw,17px)}.logout-button svg{width:clamp(15px,.78vw,19px);height:clamp(15px,.78vw,19px)}.content{max-width:min(1800px,calc(100vw - 360px));padding:clamp(36px,2vw,54px) clamp(32px,2vw,50px)}.page-heading h1,.content>h1{font-size:clamp(28px,1.8vw,40px)}.kicker,.subheading{font-size:clamp(14px,.76vw,18px)}.el-button,.el-input__inner,.el-select__placeholder,.el-table,.el-table .cell,.el-tabs__item,.el-pagination,.el-form-item__label,.el-dialog__title{font-size:var(--ui-control-size)}.el-button{min-height:clamp(32px,1.9vw,42px)}.toolbar{gap:clamp(12px,.8vw,20px);margin:clamp(16px,1vw,24px) 0}.toolbar .el-input{width:min(420px,100%)}}
@media(min-width:651px) and (max-width:1100px){.sidebar{width:220px}.content{max-width:100%;padding:30px 24px}.workspace>header{padding:0 20px}}
@media(max-width:650px){:root{--ui-navigation-size:clamp(12px,3.3vw,14px)}.nav-leading{font-size:var(--ui-navigation-size)}.submenu button{font-size:var(--ui-navigation-size)}.content{padding:clamp(24px,5vw,32px) 18px}}
.page-actions{display:flex;justify-content:flex-end;align-items:center;margin:0 0 10px}
.shell .content .el-tab-pane,.shell .content section > div:has(> .toolbar + .el-table),.shell .content .list-panel,.shell .content section .pane{min-height:min(620px,calc(100vh - 200px));padding:14px;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:4px;box-shadow:var(--el-box-shadow-lighter)}
.shell .content section .toolbar{display:flex;align-items:center;justify-content:flex-start;gap:10px;margin:0 0 8px;padding:0;border:0;border-radius:0;background:transparent;box-shadow:none}
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
.shell{height:100vh;height:100dvh;min-height:0;overflow:hidden}
.sidebar{height:100%;min-height:0;overflow:hidden}
.brand{flex:0 0 auto}
.primary-nav{flex:1 1 auto;min-height:0;align-content:start;overflow-x:hidden;overflow-y:auto;overscroll-behavior:contain;scrollbar-width:thin;scrollbar-color:rgba(171,191,228,.35) transparent}
.sidebar-foot{flex:0 0 auto}
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
  .sidebar-foot{display:flex}
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
