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
const loginError = ref('')
const loggingIn = ref(false)
async function loadSession() {
  const response = await fetch('/api/session', { credentials: 'same-origin' })
  user.value = response.ok ? (await response.json()).username : null
}
async function signIn() {
  loggingIn.value = true; loginError.value = ''
  try { await login(username.value, password.value); password.value = ''; await loadSession() }
  catch (error) { loginError.value = error.message }
  finally { loggingIn.value = false }
}
async function signOut() {
  if (!(await canLeave())) return
  try { const response = await authorizedRequest('/api/logout', { method: 'POST' }); if (!response.ok && response.status !== 401) throw new Error(); user.value = null }
  catch { loginError.value = '退出失败，请检查服务连接' }
}

const section = ref('任务与工作流')
const menu = ['平台概览', '数据资产', '任务与工作流', '实时同步', '运行运维', '数据服务', '数据查询', '数据报表', '系统管理']
const taskStudio = ref(null)
async function canLeave() {
  if (section.value !== '任务与工作流' || !taskStudio.value?.hasUnsavedWorkflow()) return true
  try { await ElMessageBox.confirm('当前工作流编排尚未保存，离开后修改将丢失。', '确认离开', { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' }); return true } catch { return false }
}
async function navigate(item) { if (item !== section.value && await canLeave()) section.value = item }
onMounted(async () => {
  try { await loadSession() } catch { user.value = null } finally { sessionLoading.value = false }
  try { const response = await fetch('/api/health'); if (!response.ok || (await response.json()).status !== 'UP') throw new Error(); status.value = '服务正常' }
  catch { status.value = '服务未连接' }
})
</script>

<template>
  <main v-if="sessionLoading" class="login-screen">正在检查登录状态…</main>
  <main v-else-if="!user" class="login-screen"><form class="login-form" @submit.prevent="signIn"><h1>数据治理平台</h1><p>登录后管理数据与处理任务</p><label>用户名<el-input v-model="username" autocomplete="username" required /></label><label>密码<el-input v-model="password" type="password" autocomplete="current-password" required show-password /></label><el-alert v-if="loginError" :title="loginError" type="error" :closable="false" /><el-button type="primary" native-type="submit" :loading="loggingIn">登录</el-button></form></main>
  <main v-else class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">数</span><div><strong>数据治理平台</strong><small>DATA OPERATIONS</small></div></div>
      <p class="nav-label">工作空间</p>
      <nav aria-label="平台导航"><button v-for="item in menu" :key="item" :class="{ active: section === item }" @click="navigate(item)">{{ item }}<span v-if="!['数据资产', '任务与工作流', '系统管理', '数据查询', '数据报表', '数据服务', '实时同步'].includes(item)" class="pending">待接入</span></button></nav>
      <div class="sidebar-foot">单节点工作空间</div>
    </aside>
    <section class="workspace">
      <header><span>工作空间 <span class="slash">/</span> {{ section }}</span><span class="status"><i :class="{ offline: status !== '服务正常' }"></i>{{ status }}　{{ user }} <button @click="signOut">退出</button></span></header>
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
.login-screen{min-height:100vh;display:grid;place-items:center;background:#eef4f3}.login-form{width:min(400px,90vw);background:white;border:1px solid #dbe8e5;padding:36px;display:grid;gap:18px}.login-form h1{margin:0;font-size:24px}.login-form p{margin:0;color:#71838a;font-size:14px}.login-form label{display:grid;gap:8px;font-size:13px}

*{box-sizing:border-box}body{margin:0;background:#f3f6f7;color:#203741;font-family:"PingFang SC","Microsoft YaHei",sans-serif}button{font:inherit}.shell{min-height:100vh;display:flex}.sidebar{width:232px;background:#173b47;color:#e8f2f2;padding:26px 16px;flex-shrink:0;display:flex;flex-direction:column}.brand{display:flex;gap:12px;align-items:center;padding:0 8px}.brand-mark{display:grid;place-items:center;background:#b8e1d6;color:#173b47;font-weight:700;width:36px;height:36px;border-radius:8px}.brand strong{display:block;font-size:16px}.brand small{display:block;font-size:9px;letter-spacing:1px;opacity:.6;margin-top:4px}.nav-label{font-size:12px;color:#9eb8bd;margin:50px 12px 12px}.sidebar nav{display:grid;gap:3px}.sidebar nav button{border:0;background:transparent;color:#c4d5d7;text-align:left;padding:12px;border-radius:6px;cursor:pointer;display:flex;justify-content:space-between}.sidebar nav button:hover,.sidebar nav button.active{background:#305662;color:white}.pending{font-size:11px;opacity:.65}.sidebar-foot{margin:auto 12px 0;padding-top:40px;font-size:12px;color:#9eb8bd}.workspace{min-width:0;flex:1}header{height:64px;background:#fff;border-bottom:1px solid #e2ebed;padding:0 36px;display:flex;align-items:center;justify-content:space-between;color:#6b7e85;font-size:13px}.slash{margin:0 8px;color:#b5c5c8}.status{display:flex;align-items:center;gap:8px}.status i{height:7px;width:7px;border-radius:50%;background:#55a590}.status i.offline{background:#dd9d5d}.content{max-width:1500px;margin:auto;padding:40px 36px}.page-heading{display:flex;justify-content:space-between;align-items:end;gap:20px;margin-bottom:24px}.kicker{font-size:13px;color:#63867e;margin:0 0 8px}.page-heading h1,.content>h1{font-size:28px;margin:0;font-weight:650}.subheading{margin:9px 0 0;color:#71838a;font-size:14px}.feedback{margin-top:12px}.editor{display:grid;grid-template-columns:minmax(0,1fr) 310px;border:1px solid #dae5e5;background:white;margin-top:22px;min-height:510px}.diagram{background:#fbfdfc;min-width:0}.panel-head{padding:23px 25px;border-bottom:1px solid #e6eeed;display:flex;justify-content:space-between;gap:12px}.panel-head h2{font-size:16px;margin:0}.panel-head p{font-size:12px;color:#8a999d;margin:7px 0 0}.count{font-size:12px;color:#66817c}.empty{text-align:center;padding:95px 20px;color:#71838a}.empty-symbol{margin:0 auto 16px;border:1px dashed #a9c7bf;color:#669a8c;width:46px;height:46px;display:grid;place-items:center;font-size:25px;border-radius:9px}.empty h3{color:#35505a;font-size:15px}.empty p,.muted{font-size:13px;color:#8a999d}.task-grid{padding:26px;display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:12px}.task{background:white;border:1px solid #d9e8e4;border-radius:7px;padding:15px;display:flex;align-items:center;gap:12px;min-width:0}.task-number{background:#e4f2ed;color:#448b76;font-size:12px;padding:7px;border-radius:5px}.task div{min-width:0;flex:1}.task strong{font-size:14px;display:block;overflow-wrap:anywhere}.task small{display:block;color:#91a3a2;margin-top:5px}.remove{border:0;background:none;color:#91a3a2;cursor:pointer;font-size:20px}.remove:hover{color:#bc5555}.inspector{border-left:1px solid #e6eeed;min-width:0}.form-section{padding:20px 24px;border-bottom:1px solid #edf2f1}.form-section h3{margin:0 0 14px;font-size:14px}.form-section h3 span{color:#91a3a2;font-weight:400}.form-section label{display:block;color:#74878a;font-size:12px;margin:12px 0 6px}.full{width:100%;margin-top:10px}.form-section label+.full{margin-top:0}.edge{display:flex;justify-content:space-between;align-items:center;gap:6px;font-size:13px;overflow-wrap:anywhere}.edge b{color:#5d9b89}.unavailable{margin-top:24px;padding:30px;background:#fff;border:1px solid #e1e9e8}.unavailable h2{font-size:16px}.unavailable p{font-size:14px;color:#71838a;margin-bottom:20px}@media(max-width:900px){.editor{grid-template-columns:1fr}.inspector{border-left:0;border-top:1px solid #e6eeed}}@media(max-width:650px){.shell{display:block}.sidebar{width:auto;padding:15px}.sidebar nav{display:flex;overflow:auto;margin-top:16px}.sidebar nav button{white-space:nowrap}.nav-label,.sidebar-foot,.pending{display:none}.content{padding:28px 18px}header{padding:0 18px}.page-heading{align-items:start;flex-direction:column}}
</style>
