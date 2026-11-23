<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { authorizedRequest } from './auth.js'
import WorkflowCanvas from './WorkflowCanvas.vue'
import RefreshIcon from './RefreshIcon.vue'
import { addTask, addEdge, removeTask, clearRemovedSelection, workflowDraftKey, workflowDraftChanged } from './workflow.js'
import { usePagination } from './pagination.js'
const can = inject('canPermission', () => false)
const sources = ref([]), tasks = ref([]), workflows = ref([]), schedules = ref([]), error = ref(''), message = ref(''), busy = ref(false)
const workflowDetail = ref(false), selectedNode = ref(null), historyDialog = ref(false), runDialog = ref(false), backfillDialog = ref(false), scheduleDialog = ref(false)
const workflow = ref({ name: '', graph: { tasks: [], edges: [] } }), selectedTask = ref(null), from = ref(''), to = ref('')
const historyWorkflow = ref(null), historyRuns = ref([]), historyState = ref(''), historyDate = ref(''), historyCanLoadMore = ref(false), historyFilterKey = ref('')
const editingWorkflow = ref(null)
const workflowSearch = ref(''), workflowFilter = ref(''), scheduleSearch = ref(''), scheduleFilter = ref('')
function matchesListQuery(row, fields, query) { return !query || fields.some(field => String(row[field] ?? '').toLocaleLowerCase().includes(query)) }
const filteredWorkflows = computed(() => workflows.value.filter(row => matchesListQuery(row, ['name', 'version'], workflowFilter.value.toLocaleLowerCase())))
const filteredSchedules = computed(() => schedules.value.filter(row => matchesListQuery(row, ['workflowId', 'cron', 'zone', 'enabled'], scheduleFilter.value.toLocaleLowerCase())))
const workflowPaging = usePagination(filteredWorkflows), schedulePaging = usePagination(filteredSchedules)
function applyWorkflowQuery() { workflowFilter.value = workflowSearch.value.trim(); workflowPaging.page.value = 1 }
function applyScheduleQuery() { scheduleFilter.value = scheduleSearch.value.trim(); schedulePaging.page.value = 1 }
const businessDate = ref(new Date().toLocaleDateString('sv-SE')), runId = ref(null), run = ref(null)
const scheduleForm = ref({ workflowId: null, cron: '0 0 0 * * *', zone: 'Asia/Shanghai' })
const backfillWorkflow = ref(null), backfillDates = ref([]), backfillRuns = ref([]), backfillBusy = ref(false), scheduleBusy = ref(false)
let workflowBaseline = workflowDraftKey(workflow.value), runRequestSequence = 0
const hasUnsavedWorkflow = () => workflowDetail.value && workflowDraftChanged(workflow.value, workflowBaseline)
defineExpose({ hasUnsavedWorkflow })
function warnOnUnload(event) { if (hasUnsavedWorkflow()) { event.preventDefault(); event.returnValue = '' } }
async function leaveWorkflow() {
  if (hasUnsavedWorkflow()) { try { await ElMessageBox.confirm('当前编排尚未保存，离开后修改将丢失。', '确认离开', { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' }) } catch { return } }
  workflowDetail.value = false; resetWorkflow()
}
function openWorkflowCreate() { resetWorkflow(); workflowBaseline = workflowDraftKey(workflow.value); workflowDetail.value = true; selectedNode.value = null }
async function get(url) { const response = await fetch(url, { credentials: 'same-origin' }); if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有相应权限' : '读取失败，请检查连接'); return response.json() }
async function post(url, body) { const response = await authorizedRequest(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }); if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有相应权限' : '保存或运行失败，请检查配置'); return response.json() }
async function reload() { try { [sources.value, tasks.value, workflows.value, schedules.value] = await Promise.all([can('datasource.source.read') ? get('/api/data-sources') : Promise.resolve([]), can('workflow.task.read') ? get('/api/tasks') : Promise.resolve([]), can('workflow.workflow.read') ? get('/api/workflows') : Promise.resolve([]), can('workflow.schedule.read') ? get('/api/schedules') : Promise.resolve([])]) } catch (e) { error.value = e.message } }
function addNode() {
  const selected = tasks.value.find(t => t.id === selectedTask.value)
  if (!selected) { error.value = '请选择任务'; return }
  if (selected.version < 1) { error.value = '该任务尚未发布，请先在数据处理模块发布'; return }
  try { const graph = addTask(workflow.value.graph, selected.name); graph.tasks[graph.tasks.length - 1].taskId = selected.id; graph.tasks[graph.tasks.length - 1].version = selected.version; workflow.value.graph = graph; error.value = '' } catch (e) { error.value = e.message }
}
function connectCanvas({ from: source, to: target }) { try { workflow.value.graph = addEdge(workflow.value.graph, source, target); error.value = '' } catch (e) { error.value = e.message } }
function resetWorkflow() { workflow.value = { name: '', graph: { tasks: [], edges: [] } }; editingWorkflow.value = null; from.value = ''; to.value = '' }
function moveNode({ id, x, y }) { const node = workflow.value.graph.tasks.find(n => n.id === id); if (node) { node.x = x; node.y = y } }
async function editWorkflow(row) {
  try {
    const definition = await get(`/api/workflows/${row.id}/versions/${row.version}`)
    workflow.value = { name: definition.name, graph: { tasks: definition.nodes.map(n => ({ id: n.key, taskId: n.taskId, version: n.taskVersion, x: n.x, y: n.y, name: tasks.value.find(t => t.id === n.taskId)?.name ?? `任务 ${n.taskId}` })), edges: definition.edges } }
    editingWorkflow.value = row.id; workflowBaseline = workflowDraftKey(workflow.value); workflowDetail.value = true; selectedNode.value = null; error.value = ''; message.value = `正在编辑 ${row.name}，保存将创建新版本`
  } catch (e) { error.value = e.message }
}
function removeNode(id) { workflow.value.graph = removeTask(workflow.value.graph, id); const selection = clearRemovedSelection(from.value, to.value, id); from.value = selection.from; to.value = selection.to }
function connect() { try { workflow.value.graph = addEdge(workflow.value.graph, from.value, to.value); from.value = ''; to.value = ''; error.value = '' } catch (e) { error.value = e.message } }
async function saveWorkflow() {
  if (busy.value) return
  error.value = ''; message.value = ''; busy.value = true
  try {
    const graph = workflow.value.graph
    await post(editingWorkflow.value ? `/api/workflows/${editingWorkflow.value}/versions` : '/api/workflows', { name: workflow.value.name, nodes: graph.tasks.map(n => ({ key: n.id, taskId: n.taskId, taskVersion: n.version, x: n.x ?? null, y: n.y ?? null })), edges: graph.edges })
    resetWorkflow(); workflowDetail.value = false; message.value = '工作流已保存'; await reload()
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
async function loadHistory(row, more = false) {
  try {
    if (row) historyWorkflow.value = row
    if (!historyWorkflow.value) return
    const filterKey = JSON.stringify([historyWorkflow.value.id, historyState.value, historyDate.value])
    if (more && filterKey !== historyFilterKey.value) more = false
    const params = new URLSearchParams({ limit: '50' })
    if (historyState.value) params.set('state', historyState.value)
    if (historyDate.value) params.set('date', historyDate.value)
    if (more && historyRuns.value.length) params.set('beforeId', historyRuns.value.at(-1).id)
    const page = await get(`/api/workflows/${historyWorkflow.value.id}/runs?${params}`)
    historyRuns.value = more ? [...historyRuns.value, ...page] : page; historyCanLoadMore.value = page.length === 50; historyFilterKey.value = filterKey; historyDialog.value = true; error.value = ''
  } catch (e) { error.value = e.message }
}
async function start(w) { try { error.value = ''; const result = await post(`/api/workflows/${w.id}/runs`, { version: w.version, businessDate: businessDate.value }); runId.value = result.id; run.value = null; runDialog.value = await refreshRun() } catch (e) { error.value = e.message } }
async function backfill() {
  if (backfillBusy.value) return
  backfillBusy.value = true
  try {
    const selected = workflows.value.find(w => w.id === backfillWorkflow.value)
    if (!selected || backfillDates.value?.length !== 2) throw new Error('请选择工作流和日期范围')
    const result = await post(`/api/workflows/${selected.id}/backfill`, { version: selected.version, startDate: backfillDates.value[0], endDate: backfillDates.value[1] })
    backfillRuns.value = result.ids; error.value = ''; message.value = `已创建 ${result.ids.length} 个补跑实例`
  } catch (e) { error.value = e.message } finally { backfillBusy.value = false }
}
async function showRun(id) { runId.value = id; run.value = null; runDialog.value = await refreshRun() }
async function createSchedule() {
  if (scheduleBusy.value) return
  scheduleBusy.value = true
  try {
    const selected = workflows.value.find(w => w.id === scheduleForm.value.workflowId)
    if (!selected) throw new Error('请选择工作流')
    await post(`/api/workflows/${selected.id}/schedules`, { version: selected.version, cron: scheduleForm.value.cron, zone: scheduleForm.value.zone })
    message.value = '定时计划已保存'; scheduleDialog.value = false; error.value = ''; await reload()
  } catch (e) { error.value = e.message } finally { scheduleBusy.value = false }
}
async function toggleSchedule(schedule) {
  try {
    const response = await authorizedRequest(`/api/schedules/${schedule.id}/enabled`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled: !schedule.enabled }) })
    if (!response.ok) throw new Error('更新定时计划失败')
    await reload()
  } catch (e) { error.value = e.message }
}
async function retryRun() {
  try {
    const response = await authorizedRequest(`/api/runs/${runId.value}/retry`, { method: 'POST' })
    if (!response.ok) throw new Error('重试失败，请确认实例处于失败或中断状态')
    await refreshRun()
  } catch (e) { error.value = e.message }
}
async function refreshRun() {
  const id = runId.value, request = ++runRequestSequence
  try { const result = await get(`/api/runs/${id}`); if (request !== runRequestSequence) return false; run.value = result; return true }
  catch (e) { if (request === runRequestSequence) { run.value = null; error.value = e.message }; return false }
}
onMounted(() => { window.addEventListener('beforeunload', warnOnUnload); reload() })
onBeforeUnmount(() => window.removeEventListener('beforeunload', warnOnUnload))
</script>
<template>
<section>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><el-alert v-if="message" :title="message" type="success" :closable="false" show-icon/>
  <el-alert type="info" :closable="false" show-icon title="任务（组件编排）请在“数据处理 · 处理任务”中创建与发布；此处管理工作流依赖、定时与补跑。" style="margin-bottom:12px"/>
  <el-tabs>
    <el-tab-pane v-if="can('workflow.workflow.read') || can('workflow.workflow.create')" label="工作流">
      <template v-if="!workflowDetail"><div class="toolbar list-query-toolbar"><div class="list-query-controls"><el-input v-model="workflowSearch" clearable placeholder="搜索工作流名称或版本" @keyup.enter="applyWorkflowQuery"/><el-button type="primary" @click="applyWorkflowQuery">查询</el-button><small>已加载 {{ workflowPaging.total.value }} 条</small></div><div class="list-query-actions"><el-button v-if="can('workflow.schedule.backfill') && can('workflow.workflow.read')" @click="error = ''; backfillDialog = true">按日期补跑</el-button><el-button v-if="can('workflow.workflow.create') && can('workflow.task.read')" type="primary" @click="openWorkflowCreate">新增</el-button><el-button class="refresh-button" title="刷新" aria-label="刷新" @click="reload"><RefreshIcon /></el-button></div></div>
        <div class="row"><span>业务日期</span><el-date-picker v-model="businessDate" type="date" value-format="YYYY-MM-DD"/></div>
        <el-table :data="workflowPaging.visible.value" empty-text="暂无工作流"><el-table-column prop="name" label="工作流名称"/><el-table-column prop="version" label="版本"/><el-table-column label="操作" min-width="250"><template #default="scope"><el-button v-if="can('workflow.workflow.update')" text type="primary" @click="editWorkflow(scope.row)">编辑编排</el-button><el-button v-if="can('workflow.workflow.execute')" text type="primary" @click="start(scope.row)">运行</el-button><el-button v-if="can('workflow.workflow.read')" text type="primary" @click="loadHistory(scope.row)">运行历史</el-button></template></el-table-column></el-table>
        <el-pagination v-model:current-page="workflowPaging.page.value" v-model:page-size="workflowPaging.pageSize.value" :total="workflowPaging.total.value" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next"/>
      </template>
      <div v-else class="pane"><div class="toolbar"><div><el-button text @click="leaveWorkflow">← 返回工作流列表</el-button><strong>{{ editingWorkflow ? '编辑工作流' : '新建工作流' }}</strong></div></div><el-form label-position="top" @submit.prevent="saveWorkflow"><el-form-item label="工作流名称"><el-input v-model="workflow.name" :disabled="!!editingWorkflow" /></el-form-item><div class="row"><el-select v-model="selectedTask" placeholder="选择已发布任务"><el-option v-for="t in tasks.filter(x => x.version > 0)" :key="t.id" :label="`${t.name}（版本 ${t.version}）`" :value="t.id"/></el-select><el-button @click="addNode">添加节点</el-button></div><p>拖动节点调整布局；从节点下方连接点拖至后续节点上方建立依赖，也可以使用下方表单。</p><WorkflowCanvas :graph="workflow.graph" @move="moveNode" @connect="connectCanvas" @select="selectedNode = $event" /><p v-if="selectedNode">已选择节点：{{ workflow.graph.tasks.find(n => n.id === selectedNode)?.name }}；工作流保留所选任务的固定版本。</p><div v-for="n in workflow.graph.tasks" :key="n.id" class="node">{{ n.name }}（版本 {{ n.version }}）<el-button text @click="removeNode(n.id)">删除</el-button></div><div class="row"><el-select v-model="from" placeholder="前置节点"><el-option v-for="n in workflow.graph.tasks" :key="n.id" :label="n.name" :value="n.id"/></el-select><el-select v-model="to" placeholder="后续节点"><el-option v-for="n in workflow.graph.tasks" :key="n.id" :label="n.name" :value="n.id"/></el-select><el-button @click="connect">添加依赖</el-button></div><div v-for="(edge, index) in workflow.graph.edges" :key="edge.from + edge.to" class="node">{{ workflow.graph.tasks.find(n => n.id === edge.from)?.name }} → {{ workflow.graph.tasks.find(n => n.id === edge.to)?.name }}<el-button text @click="workflow.graph.edges.splice(index, 1)">删除</el-button></div><el-button v-if="editingWorkflow ? can('workflow.workflow.update') : can('workflow.workflow.create')" type="primary" native-type="submit" :loading="busy">保存工作流</el-button><el-button @click="leaveWorkflow">返回列表</el-button></el-form></div>
    </el-tab-pane>
    <el-tab-pane v-if="can('workflow.schedule.read') || can('workflow.schedule.create')" label="定时计划"><div class="toolbar list-query-toolbar"><div class="list-query-controls"><el-input v-model="scheduleSearch" clearable placeholder="搜索工作流编号、Cron 或时区" @keyup.enter="applyScheduleQuery"/><el-button type="primary" @click="applyScheduleQuery">查询</el-button><small>已加载 {{ schedulePaging.total.value }} 条</small></div><div class="list-query-actions"><el-button v-if="can('workflow.schedule.create') && can('workflow.workflow.read')" type="primary" @click="error = ''; scheduleDialog = true">新增</el-button><el-button class="refresh-button" title="刷新" aria-label="刷新" @click="reload"><RefreshIcon /></el-button></div></div><el-table :data="schedulePaging.visible.value"><el-table-column prop="workflowId" label="工作流 ID"/><el-table-column prop="cron" label="Cron"/><el-table-column prop="zone" label="时区"/><el-table-column label="下次触发"><template #default="scope">{{ new Date(scope.row.nextFireEpoch).toLocaleString('zh-CN', { timeZone: scope.row.zone }) }}</template></el-table-column><el-table-column label="状态"><template #default="scope">{{ scope.row.enabled ? '启用' : '停用' }}</template></el-table-column><el-table-column label="操作"><template #default="scope"><el-button v-if="can('workflow.schedule.update')" text @click="toggleSchedule(scope.row)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column></el-table><el-pagination v-model:current-page="schedulePaging.page.value" v-model:page-size="schedulePaging.pageSize.value" :total="schedulePaging.total.value" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next"/></el-tab-pane>
  </el-tabs>
  <el-dialog v-model="historyDialog" :title="`${historyWorkflow?.name || ''} 运行历史`" width="min(1000px, 96vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><div class="row"><el-select v-model="historyState" clearable placeholder="全部状态"><el-option v-for="state in ['PENDING','RUNNING','WAITING','SUCCEEDED','FAILED','INTERRUPTED']" :key="state" :label="state" :value="state"/></el-select><el-date-picker v-model="historyDate" type="date" value-format="YYYY-MM-DD" placeholder="业务日期"/><el-button @click="loadHistory(null)">查询</el-button></div><el-table :data="historyRuns"><el-table-column prop="id" label="实例"/><el-table-column prop="workflowVersion" label="版本"/><el-table-column prop="businessDate" label="业务日期"/><el-table-column prop="state" label="状态"/><el-table-column prop="createdAt" label="创建时间"/><el-table-column label="操作"><template #default="scope"><el-button text type="primary" @click="showRun(scope.row.id)">详情</el-button></template></el-table-column></el-table><el-button v-if="historyCanLoadMore" @click="loadHistory(null, true)">加载更多</el-button></el-dialog>
  <el-dialog v-model="runDialog" :title="`运行实例 ${run?.id || ''}`" width="min(850px, 95vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><div v-if="run"><div class="row"><strong>状态：{{ run.state }}</strong><el-button @click="refreshRun">刷新</el-button><el-popconfirm v-if="can('workflow.workflow.retry') && ['FAILED','INTERRUPTED'].includes(run.state)" title="重试可能重复写入，确认已检查幂等策略？" @confirm="retryRun"><template #reference><el-button type="warning">重试失败节点</el-button></template></el-popconfirm></div><el-table :data="run.nodes"><el-table-column prop="key" label="节点标识"/><el-table-column prop="state" label="状态"/><el-table-column label="执行记录"><template #default="scope"><div v-for="a in scope.row.attempts" :key="a.attempt">第 {{ a.attempt }} 次：{{ a.state }} {{ a.errorMessage || '' }}</div></template></el-table-column></el-table></div></el-dialog>
  <el-dialog v-model="backfillDialog" title="按日期补跑" width="min(700px, 95vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><p>每次最多补跑 31 天。任务须配置业务日期参数，请确认数据范围和写入幂等策略。</p><div class="row"><el-select v-model="backfillWorkflow" placeholder="选择工作流"><el-option v-for="w in workflows" :key="w.id" :label="`${w.name}（版本 ${w.version}）`" :value="w.id"/></el-select><el-date-picker v-model="backfillDates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期"/><el-popconfirm v-if="can('workflow.schedule.backfill')" title="补跑可能重复写入，确认已检查幂等策略？" @confirm="backfill"><template #reference><el-button type="primary" :loading="backfillBusy" :disabled="backfillBusy">创建补跑</el-button></template></el-popconfirm></div><div v-if="backfillRuns.length">运行实例：<el-button v-for="id in backfillRuns" :key="id" text @click="showRun(id)">{{ id }}</el-button></div></el-dialog>
  <el-dialog v-model="scheduleDialog" title="新建定时计划" width="min(650px, 95vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><p>使用六段 Cron 表达式。停用后重新启用从当前时间计算。</p><el-form label-position="top" @submit.prevent="createSchedule"><el-form-item label="工作流"><el-select v-model="scheduleForm.workflowId"><el-option v-for="w in workflows" :key="w.id" :label="`${w.name}（版本 ${w.version}）`" :value="w.id"/></el-select></el-form-item><el-form-item label="Cron（秒 分 时 日 月 周）"><el-input v-model="scheduleForm.cron"/></el-form-item><el-form-item label="时区"><el-input v-model="scheduleForm.zone"/></el-form-item><el-button v-if="can('workflow.schedule.create') && can('workflow.workflow.read')" type="primary" native-type="submit" :loading="scheduleBusy" :disabled="scheduleBusy">保存计划</el-button></el-form></el-dialog>
</section>
</template>
<style scoped>.pane{background:white;padding:22px;margin:16px 0;border:1px solid #e1eae8}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 0}.toolbar small{display:block;margin-top:5px;color:#728582}.row{display:flex;gap:12px;align-items:center;margin:14px 0}.node{display:flex;justify-content:space-between;align-items:center;padding:6px 10px;border-bottom:1px solid #e8efee}.el-pagination{margin-top:18px;justify-content:flex-end}@media(max-width:700px){.row,.toolbar{flex-wrap:wrap}}</style>