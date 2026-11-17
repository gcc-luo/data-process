<script setup>
import { onMounted, ref } from 'vue'
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

const section = ref('任务与工作流')
const menu = [
  { label: '平台概览', icon: 'M4 4h7v7H4z M13 4h7v5h-7z M13 11h7v9h-7z M4 13h7v7H4z' },
  { label: '数据资产', icon: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3Zm0 0v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6m-16 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6' },
  { label: '任务与工作流', icon: 'M6 4v4a4 4 0 0 0 4 4h4a4 4 0 0 1 4 4v4m-12 0v-3m12-13v3' },
  { label: '实时同步', icon: 'M3 12h4l3-7 4 14 3-7h4' },
  { label: '运行运维', icon: 'M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4m0-12.8L17 7M7 17l-1.4 1.4M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z' },
  { label: '数据服务', icon: 'M12 3 20 7.5v9L12 21l-8-4.5v-9L12 3Zm-8 4.5 8 4.5 8-4.5m-8 4.5V21' },
  { label: '数据查询', icon: 'M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13Zm5 11 4.5 4.5' },
  { label: '数据报表', icon: 'M4 20h16M7 16v-5m5 5V5m5 11v-8' },
  { label: '系统管理', icon: 'M4 7h9m4 0h3M4 17h3m4 0h9m-7-2v4m-4-14v4m8-4v4' },
]
const pendingMenus = new Set(['平台概览', '运行运维'])
const taskStudio = ref(null)
async function canLeave() {
  if (section.value !== '任务与工作流' || !taskStudio.value?.hasUnsavedWorkflow()) return true
  try { await ElMessageBox.confirm('当前工作流编排尚未保存，离开后修改将丢失。', '确认离开', { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' }); return true } catch { return false }
}
async function navigate(item) { if (item !== section.value && await canLeave()) section.value = item }
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
  <main v-else class="shell">
    <aside class="sidebar">
      <div class="brand"><svg class="brand-cube" viewBox="0 0 48 48" aria-hidden="true"><path fill="#55D6F0" d="M24 2 43 13v22L24 46 5 35V13L24 2Z"/><path fill="#1685E7" d="M24 2v22L5 13 24 2Z"/><path fill="#36BFE8" d="M24 24 43 13v22L24 46V24Z"/><path fill="#195CB8" d="M5 13 24 24v22L5 35V13Z"/><path fill="#D8F8FF" d="m24 15 8 4.5v9L24 33l-8-4.5v-9l8-4.5Z"/></svg><div><strong>DataSphere</strong><small>DATA OPERATIONS</small></div></div>
      <nav class="primary-nav" aria-label="平台导航">
        <button v-for="item in menu" :key="item.label" type="button" :class="{ active: section === item.label }" :aria-current="section === item.label ? 'page' : undefined" @click="navigate(item.label)">
          <span class="nav-leading"><svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true"><path :d="item.icon" /></svg><span>{{ item.label }}</span></span>
          <span v-if="pendingMenus.has(item.label)" class="pending">待接入</span>
        </button>
      </nav>
      <div class="sidebar-foot"><span class="workspace-dot"></span><span>单节点工作空间</span><small>LOCAL</small></div>
    </aside>
    <section class="workspace">
      <header>
        <div class="breadcrumbs"><span>工作空间</span><span class="slash">/</span><strong>{{ section }}</strong></div>
        <div class="header-actions">
          <span class="status"><i :class="{ offline: status !== '服务正常' }"></i>{{ status }}</span>
          <span class="account-chip"><span class="account-avatar">{{ user?.slice(0, 1)?.toUpperCase() }}</span><span>{{ user }}</span></span>
          <button class="logout-button" type="button" @click="signOut"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M8 4H4.8A1.8 1.8 0 0 0 3 5.8v8.4A1.8 1.8 0 0 0 4.8 16H8m3-9 4 3-4 3m4-3H7" /></svg><span>退出</span></button>
        </div>
      </header>
      <div v-if="section === '数据资产'" class="content"><DataSources /></div>
      <div v-else-if="section === '任务与工作流'" class="content"><TaskStudio ref="taskStudio" /></div>
      <div v-else-if="section === '系统管理'" class="content"><Organization /></div>
      <div v-else-if="section === '数据查询'" class="content"><DataExplorer /></div>
      <div v-else-if="section === '数据报表'" class="content"><Reports /></div>
      <div v-else-if="section === '数据服务'" class="content"><Services /></div>
      <div v-else-if="section === '实时同步'" class="content"><Realtime /></div>
      <div v-else class="content"><p class="kicker">工作空间</p><h1>{{ section }}</h1><div class="unavailable"><h2>功能尚未接入</h2><p>该模块仍在开发，可前往任务与工作流查看已接入的功能。</p><el-button @click="navigate('任务与工作流')">前往任务与工作流</el-button></div></div>
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
</style>
