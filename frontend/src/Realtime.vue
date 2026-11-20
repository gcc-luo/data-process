<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { authorizedRequest } from './auth.js'
import RefreshIcon from './RefreshIcon.vue'
import { usePagination } from './pagination.js'
const can = inject('canPermission', () => false)
const sources = ref([]), jobs = ref([]), error = ref(''), message = ref(''), busy = ref(false)
const createOpen = ref(false)
const jobSearch = ref(''), jobFilter = ref('')
const filteredJobs = computed(() => jobs.value.filter(job => !jobFilter.value || [job.name, job.state, job.sourceTable, job.targetTable].some(value => String(value ?? '').toLocaleLowerCase().includes(jobFilter.value.toLocaleLowerCase()))))
const { page: jobPage, visible: visibleJobs } = usePagination(filteredJobs)
function applyJobQuery() { jobFilter.value = jobSearch.value.trim(); jobPage.value = 1 }
const settingsOpen = ref(false), settingsJob = ref(null), settingsBusy = ref(false), settings = ref({ intervalSeconds: 10, batchSize: 100 })
const form = ref({ name:'', sourceId:null, targetId:null, sourceTable:'', targetTable:'', sourceColumns:'', targetColumns:'', watermarkColumn:'', tieColumn:'', targetKeys:'', watermarkType:'LONG', batchSize:100, intervalSeconds:10 })
function columns(value) { return value.split(',').map(s => s.trim()).filter(Boolean) }
async function load() { try { const [a,b] = await Promise.all([can('datasource.source.read') ? fetch('/api/data-sources') : Promise.resolve(null), can('workflow.realtime.read') ? fetch('/api/realtime-jobs') : Promise.resolve(null)]); if (a && !a.ok || b && !b.ok) throw new Error('无法读取数据源或实时任务，请检查权限'); sources.value = a ? await a.json() : []; jobs.value = b ? await b.json() : [] } catch (e) { error.value = e.message } }
async function create() {
  error.value = ''; busy.value = true
  try {
    const f = form.value
    const config = { sourceId:f.sourceId, targetId:f.targetId, intervalSeconds:f.intervalSeconds, mapping:{ sourceTable:f.sourceTable, targetTable:f.targetTable, sourceColumns:columns(f.sourceColumns), targetColumns:columns(f.targetColumns), watermarkColumn:f.watermarkColumn, tieColumn:f.tieColumn, targetKeys:columns(f.targetKeys), watermarkType:f.watermarkType, batchSize:f.batchSize } }
    const response = await authorizedRequest('/api/realtime-jobs', { method:'POST', headers:{ 'Content-Type':'application/json' }, body:JSON.stringify({ name:f.name, config }) })
    if (!response.ok) throw new Error('创建失败，请检查数据源、字段映射和主键配置')
    message.value = '任务已创建，启动前请确认增量字段满足顺序要求'; createOpen.value = false; form.value.name = ''; await load()
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
async function action(job, operation) {
  try { const response = await authorizedRequest(`/api/realtime-jobs/${job.id}/${operation}`, { method:'POST' }); if (!response.ok) throw new Error('操作失败，请刷新任务状态'); message.value = operation === 'start' ? '任务已启动' : '任务已停止'; error.value = ''; await load() } catch (e) { error.value = e.message }
}
function editSettings(job) {
  settingsJob.value = job; settings.value = { intervalSeconds: job.intervalSeconds, batchSize: job.batchSize }; error.value = ''; settingsOpen.value = true
}
async function saveSettings() {
  settingsBusy.value = true
  try {
    const response = await authorizedRequest(`/api/realtime-jobs/${settingsJob.value.id}/settings`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(settings.value) })
    if (!response.ok) throw new Error(response.status === 409 ? '任务状态或配置已变化，请停止任务并刷新后重试' : response.status === 403 ? '当前账号没有任务管理权限' : '设置保存失败，请检查输入')
    settingsOpen.value = false; message.value = '轮询设置已更新，原游标保持不变'; error.value = ''; await load()
  } catch (e) { error.value = e.message } finally { settingsBusy.value = false }
}
let timer
onMounted(() => { load(); timer = setInterval(load, 10000) })
onBeforeUnmount(() => clearInterval(timer))
</script>
<template><section><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><el-alert v-if="message" :title="message" type="success" :closable="false" show-icon/><el-alert type="warning" :closable="false" show-icon title="请确认增量字段按提交顺序稳定递增，源表单列主键为数值型，目标键与目标主键一致。迟到或回拨的更新、物理删除不会自动捕获；这是轮询，不是 CDC。"/>
<el-dialog v-model="createOpen" title="新建实时任务" width="min(720px, 95vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><el-form label-position="top" @submit.prevent="create"><div class="grid"><el-form-item label="任务名称"><el-input v-model="form.name"/></el-form-item><el-form-item label="增量类型"><el-select v-model="form.watermarkType"><el-option label="整数" value="LONG"/><el-option label="无时区时间戳" value="TIMESTAMP"/></el-select></el-form-item><el-form-item label="源数据源"><el-select v-model="form.sourceId"><el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id"/></el-select></el-form-item><el-form-item label="目标数据源"><el-select v-model="form.targetId"><el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id"/></el-select></el-form-item><el-form-item label="源表（可含 schema）"><el-input v-model="form.sourceTable"/></el-form-item><el-form-item label="目标表（可含 schema）"><el-input v-model="form.targetTable"/></el-form-item><el-form-item label="源字段，按顺序逗号分隔"><el-input v-model="form.sourceColumns" placeholder="id, name"/></el-form-item><el-form-item label="目标字段，按同顺序逗号分隔"><el-input v-model="form.targetColumns" placeholder="id, name"/></el-form-item><el-form-item label="增量字段"><el-input v-model="form.watermarkColumn"/></el-form-item><el-form-item label="源表单列主键（数值型）"><el-input v-model="form.tieColumn"/></el-form-item><el-form-item label="目标主键字段，逗号分隔"><el-input v-model="form.targetKeys"/></el-form-item><el-form-item label="每批最多行数"><el-input-number v-model="form.batchSize" :min="1" :max="1000"/></el-form-item><el-form-item label="轮询间隔（秒）"><el-input-number v-model="form.intervalSeconds" :min="5" :max="3600"/></el-form-item></div><el-button type="primary" native-type="submit" :loading="busy">创建任务</el-button></el-form></el-dialog>
<div class="list-panel"><div class="toolbar list-query-toolbar"><div class="list-query-controls"><el-input v-model="jobSearch" clearable placeholder="搜索实时任务名称或状态" @keyup.enter="applyJobQuery"/><el-button type="primary" @click="applyJobQuery">查询</el-button><span>已加载 {{ filteredJobs.length }} 个实时任务</span></div><div class="list-query-actions"><el-button v-if="can('workflow.realtime.create') && can('datasource.source.read')" type="primary" @click="error = ''; createOpen = true">新增</el-button><el-button class="refresh-button" title="刷新状态" aria-label="刷新状态" @click="load"><RefreshIcon /></el-button></div></div><el-table :data="visibleJobs"><el-table-column prop="name" label="任务"/><el-table-column label="状态"><template #default="scope"><el-tag :type="scope.row.state === 'RUNNING' ? 'success' : scope.row.state === 'ERROR' ? 'danger' : 'info'" effect="light">{{ scope.row.state }}</el-tag></template></el-table-column><el-table-column prop="watermark" label="增量游标" min-width="150"/><el-table-column prop="tie" label="主键游标"/><el-table-column prop="processed" label="累计处理"/><el-table-column prop="intervalSeconds" label="间隔（秒）"/><el-table-column prop="batchSize" label="批次大小"/><el-table-column prop="lastSuccess" label="最近成功" min-width="160"/><el-table-column prop="lastError" label="错误" min-width="180"/><el-table-column label="操作" min-width="200"><template #default="scope"><el-button v-if="can('workflow.realtime.start') && scope.row.state !== 'RUNNING'" text type="primary" @click="action(scope.row,'start')">启动</el-button><el-button v-if="can('workflow.realtime.stop') && scope.row.state === 'RUNNING'" text @click="action(scope.row,'stop')">停止</el-button><el-button v-if="can('workflow.realtime.update') && ['STOPPED','ERROR'].includes(scope.row.state)" text @click="editSettings(scope.row)">设置</el-button></template></el-table-column></el-table><el-pagination v-model:current-page="jobPage" :page-size="10" :total="filteredJobs.length" layout="total, prev, pager, next" /></div><el-dialog v-model="settingsOpen" title="轮询设置" width="min(480px, 95vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><p>仅可调整间隔和批次大小；游标、数据源及字段映射保持不变。如需修改映射，请新建同步任务。</p><el-form label-position="top" @submit.prevent="saveSettings"><el-form-item label="轮询间隔（秒）"><el-input-number v-model="settings.intervalSeconds" :min="5" :max="3600"/></el-form-item><el-form-item label="每批最多行数"><el-input-number v-model="settings.batchSize" :min="1" :max="1000"/></el-form-item><el-button v-if="can('workflow.realtime.update')" type="primary" native-type="submit" :loading="settingsBusy">保存</el-button></el-form></el-dialog></section></template>
<style scoped>.header-actions{display:flex;gap:8px}.list-caption{margin:20px 0 8px;color:#637a75}.el-pagination{margin:18px 0;justify-content:flex-end}.pane{background:white;border:1px solid #e1eae8;padding:20px;margin:16px 0}.pane h2{font-size:16px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}@media(max-width:700px){.grid{grid-template-columns:1fr}}</style>
