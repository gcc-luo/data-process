<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { authorizedRequest } from './auth.js'
import WorkflowCanvas from './WorkflowCanvas.vue'
import { emptyTask, formFromTask, configurationFromForm, newParameter } from './task-edit.js'
import { addTask, addEdge, removeTask, clearRemovedSelection, workflowDraftKey, workflowDraftChanged } from './workflow.js'
import { usePagination } from './pagination.js'
const sources = ref([]), tasks = ref([]), workflows = ref([]), schedules = ref([]), error = ref(''), message = ref(''), busy = ref(false)
const task = ref(emptyTask()), editingTask = ref(null), taskDialog = ref(false), taskDetail = ref(null)
const workflowDetail = ref(false), selectedNode = ref(null), historyDialog = ref(false), taskRunsDialog = ref(false), runDialog = ref(false), backfillDialog = ref(false), scheduleDialog = ref(false)

const workflow = ref({ name: '', graph: { tasks: [], edges: [] } }), selectedTask = ref(null), from = ref(''), to = ref('')
const historyWorkflow = ref(null), historyRuns = ref([]), historyState = ref(''), historyDate = ref(''), historyCanLoadMore = ref(false), historyFilterKey = ref('')
const editingWorkflow = ref(null), selectedTaskRun = ref(null), taskRuns = ref([])
const taskPaging = usePagination(tasks), workflowPaging = usePagination(workflows), schedulePaging = usePagination(schedules), taskRunPaging = usePagination(taskRuns)
const businessDate = ref(new Date().toLocaleDateString('sv-SE')), runId = ref(null), run = ref(null)
const scheduleForm = ref({ workflowId: null, cron: '0 0 0 * * *', zone: 'Asia/Shanghai' })
const backfillWorkflow = ref(null), backfillDates = ref([]), backfillRuns = ref([]), backfillBusy = ref(false), scheduleBusy = ref(false)
let workflowBaseline = workflowDraftKey(workflow.value), runRequestSequence = 0, taskRunRequestSequence = 0
const hasUnsavedWorkflow = () => workflowDetail.value && workflowDraftChanged(workflow.value, workflowBaseline)
defineExpose({ hasUnsavedWorkflow })
function warnOnUnload(event) { if (hasUnsavedWorkflow()) { event.preventDefault(); event.returnValue = '' } }
async function leaveWorkflow() {
  if (hasUnsavedWorkflow()) { try { await ElMessageBox.confirm('当前编排尚未保存，离开后修改将丢失。', '确认离开', { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' }) } catch { return } }
  workflowDetail.value = false; resetWorkflow()
}
const taskGraph = computed(() => {
  if (!taskDetail.value) return { tasks: [], edges: [] }
  let config = {}; try { config = JSON.parse(taskDetail.value.configuration) } catch { /* display the stored task without exposing malformed configuration */ }
  const source = sources.value.find(s => s.id === config.sourceId)?.name ?? `数据源 ${config.sourceId ?? '未配置'}`
  const graph = { tasks: [{ id: 'source', name: source, x: 40, y: 120 }, { id: 'process', name: taskDetail.value.type === 'SQL' ? 'SQL 加工' : '数据库同步', x: 280, y: 120 }], edges: [{ from: 'source', to: 'process' }] }
  if (taskDetail.value.type === 'SYNC') { graph.tasks.push({ id: 'target', name: sources.value.find(s => s.id === config.targetId)?.name ?? `数据源 ${config.targetId ?? '未配置'}`, x: 520, y: 120 }); graph.edges.push({ from: 'process', to: 'target' }) }
  return graph
})
function openTaskCreate() { cancelTaskEdit(); error.value = ''; taskDialog.value = true }
function openTaskDetail(row) { taskDetail.value = row; error.value = '' }
function openWorkflowCreate() { resetWorkflow(); workflowBaseline = workflowDraftKey(workflow.value); workflowDetail.value = true; selectedNode.value = null }
async function get(url) { const response = await fetch(url, { credentials: 'same-origin' }); if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有相应权限' : '读取失败，请检查连接'); return response.json() }
async function post(url, body) { const response = await authorizedRequest(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }); if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有相应权限' : '保存或运行失败，请检查配置'); return response.json() }
async function reload() { try { [sources.value, tasks.value, workflows.value, schedules.value] = await Promise.all([get('/api/data-sources'), get('/api/tasks'), get('/api/workflows'), get('/api/schedules')]) } catch (e) { error.value = e.message } }
async function createTask() {
  if (busy.value) return
  error.value = ''; message.value = ''; busy.value = true
  try {
    const t = task.value, configuration = configurationFromForm(t)
    const url = editingTask.value ? `/api/tasks/${editingTask.value}/versions` : '/api/tasks'
    await post(url, editingTask.value ? { configuration } : { name: t.name, type: t.type, configuration })
    task.value = emptyTask(); editingTask.value = null; taskDialog.value = false; message.value = '任务已保存'; await reload(); if (taskDetail.value) taskDetail.value = tasks.value.find(t => t.id === taskDetail.value.id) ?? taskDetail.value
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
async function loadTaskRuns(row) {
  const request = ++taskRunRequestSequence
  try { const result = await get(`/api/tasks/${row.id}/runs`); if (request !== taskRunRequestSequence) return; selectedTaskRun.value = row; taskRuns.value = result; taskRunsDialog.value = true; error.value = '' }
  catch (e) { if (request === taskRunRequestSequence) error.value = e.message }
}
async function retryTaskRun(run) {
  try {
    const response = await authorizedRequest(`/api/tasks/${selectedTaskRun.value.id}/runs/${run.id}/retry`, { method: 'POST' })
    if (!response.ok) throw new Error(response.status === 409 ? '实例状态已变化，无法重试' : '重试失败，请检查权限和实例状态')
    message.value = '任务重试已提交'; await loadTaskRuns(selectedTaskRun.value)
  } catch (e) { error.value = e.message }
}
async function runTask(row) {
  try { await post(`/api/tasks/${row.id}/runs`, { version: row.version, businessDate: businessDate.value }); message.value = '任务已提交'; await loadTaskRuns(row) }
  catch (e) { error.value = e.message }
}
function editTask(row) {
  try { task.value = formFromTask(row); editingTask.value = row.id; taskDialog.value = true; message.value = `正在编辑 ${row.name}，保存将创建新版本`; error.value = '' }
  catch (e) { error.value = e.message }
}
function cancelTaskEdit() { task.value = emptyTask(); editingTask.value = null; taskDialog.value = false }
function addNode() {
  const selected = tasks.value.find(t => t.id === selectedTask.value)
  if (!selected) { error.value = '请选择任务'; return }
  try { const graph = addTask(workflow.value.graph, selected.name); graph.tasks[graph.tasks.length - 1].taskId = selected.id; graph.tasks[graph.tasks.length - 1].version = selected.version; workflow.value.graph = graph; error.value = '' } catch (e) { error.value = e.message }
}
function connectCanvas({ from: source, to: target }) {
  try { workflow.value.graph = addEdge(workflow.value.graph, source, target); error.value = '' } catch (e) { error.value = e.message }
}
function resetWorkflow() {
  workflow.value = { name: '', graph: { tasks: [], edges: [] } }; editingWorkflow.value = null; from.value = ''; to.value = ''
}
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
  <div class="page-actions"><el-button @click="reload">刷新</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><el-alert v-if="message" :title="message" type="success" :closable="false" show-icon/>
  <el-tabs>
    <el-tab-pane label="任务">
      <template v-if="!taskDetail">
        <div class="toolbar"><div><strong>任务列表</strong><small>已加载 {{ taskPaging.total.value }} 条，列表内分页</small></div><el-button type="primary" @click="openTaskCreate">新建任务</el-button></div>
        <div class="row"><span>业务日期</span><el-date-picker v-model="businessDate" type="date" value-format="YYYY-MM-DD"/></div>
        <el-table :data="taskPaging.visible.value" empty-text="暂无任务"><el-table-column prop="name" label="任务名称"/><el-table-column prop="type" label="类型"/><el-table-column prop="version" label="版本"/><el-table-column label="操作" min-width="270"><template #default="scope"><el-button text type="primary" @click="openTaskDetail(scope.row)">拓扑详情</el-button><el-button text @click="editTask(scope.row)">编辑</el-button><el-button text @click="runTask(scope.row)">运行</el-button><el-button text @click="loadTaskRuns(scope.row)">运行记录</el-button></template></el-table-column></el-table>
        <el-pagination v-model:current-page="taskPaging.page.value" v-model:page-size="taskPaging.pageSize.value" :total="taskPaging.total.value" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next"/>
      </template>
      <template v-else><div class="toolbar"><div><el-button text @click="taskDetail = null">← 返回任务列表</el-button><strong>{{ taskDetail.name }}</strong><small>版本 {{ taskDetail.version }}</small></div><el-button type="primary" @click="editTask(taskDetail)">编辑任务</el-button></div><p>拓扑展示此任务的数据源、处理方式及目标；具体参数通过编辑配置。</p><WorkflowCanvas :graph="taskGraph" readonly/><div class="row"><el-button @click="runTask(taskDetail)">运行任务</el-button><el-button @click="loadTaskRuns(taskDetail)">运行记录</el-button></div></template>
    </el-tab-pane>
    <el-tab-pane label="工作流">
      <template v-if="!workflowDetail"><div class="toolbar"><div><strong>工作流列表</strong><small>已加载 {{ workflowPaging.total.value }} 条，列表内分页</small></div><div><el-button @click="error = ''; backfillDialog = true">按日期补跑</el-button><el-button type="primary" @click="openWorkflowCreate">新建工作流</el-button></div></div>
        <div class="row"><span>业务日期</span><el-date-picker v-model="businessDate" type="date" value-format="YYYY-MM-DD"/></div>
        <el-table :data="workflowPaging.visible.value" empty-text="暂无工作流"><el-table-column prop="name" label="工作流名称"/><el-table-column prop="version" label="版本"/><el-table-column label="操作" min-width="250"><template #default="scope"><el-button text type="primary" @click="editWorkflow(scope.row)">拓扑详情</el-button><el-button text @click="start(scope.row)">运行</el-button><el-button text @click="loadHistory(scope.row)">运行历史</el-button></template></el-table-column></el-table>
        <el-pagination v-model:current-page="workflowPaging.page.value" v-model:page-size="workflowPaging.pageSize.value" :total="workflowPaging.total.value" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next"/>
      </template>
      <div v-else class="pane"><div class="toolbar"><div><el-button text @click="leaveWorkflow">← 返回工作流列表</el-button><strong>{{ editingWorkflow ? '编辑工作流' : '新建工作流' }}</strong></div></div><el-form label-position="top" @submit.prevent="saveWorkflow"><el-form-item label="工作流名称"><el-input v-model="workflow.name" :disabled="!!editingWorkflow" /></el-form-item><div class="row"><el-select v-model="selectedTask" placeholder="选择已保存任务"><el-option v-for="t in tasks" :key="t.id" :label="`${t.name}（版本 ${t.version}）`" :value="t.id"/></el-select><el-button @click="addNode">添加节点</el-button></div><p>拖动节点调整布局；从节点下方连接点拖至后续节点上方建立依赖，也可以使用下方表单。</p><WorkflowCanvas :graph="workflow.graph" @move="moveNode" @connect="connectCanvas" @select="selectedNode = $event" /><p v-if="selectedNode">已选择节点：{{ workflow.graph.tasks.find(n => n.id === selectedNode)?.name }}；工作流保留所选任务的固定版本。</p><div v-for="n in workflow.graph.tasks" :key="n.id" class="node">{{ n.name }}（版本 {{ n.version }}）<el-button text @click="removeNode(n.id)">删除</el-button></div><div class="row"><el-select v-model="from" placeholder="前置节点"><el-option v-for="n in workflow.graph.tasks" :key="n.id" :label="n.name" :value="n.id"/></el-select><el-select v-model="to" placeholder="后续节点"><el-option v-for="n in workflow.graph.tasks" :key="n.id" :label="n.name" :value="n.id"/></el-select><el-button @click="connect">添加依赖</el-button></div><div v-for="(edge, index) in workflow.graph.edges" :key="edge.from + edge.to" class="node">{{ workflow.graph.tasks.find(n => n.id === edge.from)?.name }} → {{ workflow.graph.tasks.find(n => n.id === edge.to)?.name }}<el-button text @click="workflow.graph.edges.splice(index, 1)">删除</el-button></div><el-button type="primary" native-type="submit" :loading="busy">保存工作流</el-button><el-button @click="leaveWorkflow">返回列表</el-button></el-form></div>
    </el-tab-pane>
    <el-tab-pane label="定时计划"><div class="toolbar"><div><strong>定时计划</strong><small>已加载 {{ schedulePaging.total.value }} 条，列表内分页</small></div><el-button type="primary" @click="error = ''; scheduleDialog = true">新建计划</el-button></div><el-table :data="schedulePaging.visible.value"><el-table-column prop="workflowId" label="工作流 ID"/><el-table-column prop="cron" label="Cron"/><el-table-column prop="zone" label="时区"/><el-table-column label="下次触发"><template #default="scope">{{ new Date(scope.row.nextFireEpoch).toLocaleString('zh-CN', { timeZone: scope.row.zone }) }}</template></el-table-column><el-table-column label="状态"><template #default="scope">{{ scope.row.enabled ? '启用' : '停用' }}</template></el-table-column><el-table-column label="操作"><template #default="scope"><el-button text @click="toggleSchedule(scope.row)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column></el-table><el-pagination v-model:current-page="schedulePaging.page.value" v-model:page-size="schedulePaging.pageSize.value" :total="schedulePaging.total.value" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next"/></el-tab-pane>
  </el-tabs>
  <el-dialog v-model="taskDialog" :title="editingTask ? '编辑任务版本' : '新建任务'" width="min(850px, 95vw)" destroy-on-close><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><el-form label-position="top" @submit.prevent="createTask"><div class="grid"><el-form-item label="任务名称"><el-input v-model="task.name" :disabled="!!editingTask" /></el-form-item><el-form-item label="任务类型"><el-select v-model="task.type" :disabled="!!editingTask"><el-option label="SQL 加工" value="SQL"/><el-option label="数据库同步" value="SYNC"/></el-select></el-form-item><el-form-item label="源数据源"><el-select v-model="task.sourceId"><el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id"/></el-select></el-form-item><el-form-item v-if="task.type === 'SYNC'" label="目标数据源"><el-select v-model="task.targetId"><el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id"/></el-select></el-form-item></div><template v-if="task.type === 'SQL'"><el-form-item label="SQL 语句"><el-input v-model="task.sql" type="textarea" :rows="4" /></el-form-item></template><template v-else><el-form-item label="源查询 SQL"><el-input v-model="task.sourceQuery" type="textarea" :rows="3" /></el-form-item><div class="grid"><el-form-item label="目标表"><el-input v-model="task.targetTable" placeholder="例如 schema.table" /></el-form-item><el-form-item label="目标字段，逗号分隔"><el-input v-model="task.columns" placeholder="id, name" /></el-form-item><el-form-item label="写入方式"><el-select v-model="task.writeMode"><el-option label="追加" value="APPEND"/><el-option label="按唯一键更新插入" value="UPSERT"/></el-select></el-form-item><el-form-item v-if="task.writeMode === 'UPSERT'" label="目标唯一键字段，逗号分隔"><el-input v-model="task.keys" placeholder="需与目标表主键或唯一索引完全一致" /></el-form-item><el-form-item label="批次大小"><el-input-number v-model="task.batchSize" :min="1" :max="10000" /></el-form-item></div></template><div class="parameter-editor"><div class="row"><strong>SQL 参数</strong><el-button text type="primary" :disabled="task.parameters.length >= 1000" @click="task.parameters.push(newParameter())">添加参数</el-button></div><p>按照 SQL 中占位符的顺序填写。业务日期由每次运行或补跑时传入；数字超过安全精度时请使用数据库支持的文本转换。</p><div v-for="(parameter, index) in task.parameters" :key="index" class="row parameter-row"><span>第 {{ index + 1 }} 个</span><el-select :model-value="parameter.kind" aria-label="参数类型" @change="task.parameters[index] = newParameter($event)"><el-option label="文本" value="string"/><el-option label="数字" value="number"/><el-option label="布尔" value="boolean"/><el-option label="空值" value="null"/><el-option label="业务日期" value="date"/></el-select><el-input v-if="parameter.kind === 'string'" v-model="parameter.value" aria-label="文本参数"/><el-input v-else-if="parameter.kind === 'number'" v-model="parameter.value" inputmode="decimal" placeholder="例如 12.5" aria-label="数字参数"/><el-select v-else-if="parameter.kind === 'boolean'" v-model="parameter.value" aria-label="布尔参数"><el-option label="是" :value="true"/><el-option label="否" :value="false"/></el-select><el-button text :disabled="index === 0" @click="[task.parameters[index - 1], task.parameters[index]] = [task.parameters[index], task.parameters[index - 1]]">上移</el-button><el-button text :disabled="index === task.parameters.length - 1" @click="[task.parameters[index + 1], task.parameters[index]] = [task.parameters[index], task.parameters[index + 1]]">下移</el-button><el-button text type="danger" @click="task.parameters.splice(index, 1)">删除</el-button></div></div><div><el-checkbox v-model="task.retrySafe">我已确认任务在工作流中自动重试不会造成重复或错误数据</el-checkbox><div v-if="task.retrySafe" class="row"><span>最多重试</span><el-input-number v-model="task.maxRetries" :min="1" :max="3"/><span>次，间隔（秒）</span><el-input-number v-model="task.retryDelay" :min="1" :max="3600"/></div></div><el-button type="primary" native-type="submit" :loading="busy">{{ editingTask ? "保存新版本" : "保存任务" }}</el-button><el-button v-if="editingTask" @click="cancelTaskEdit">取消编辑</el-button></el-form></el-dialog>
  <el-dialog v-model="taskRunsDialog" :title="`${selectedTaskRun?.name || ''} 运行记录`" width="min(1050px, 96vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><el-button @click="loadTaskRuns(selectedTaskRun)">刷新</el-button><el-table :data="taskRunPaging.visible.value"><el-table-column prop="id" label="实例"/><el-table-column prop="taskVersion" label="版本"/><el-table-column prop="businessDate" label="业务日期"/><el-table-column prop="state" label="状态"/><el-table-column prop="startedAt" label="开始时间"/><el-table-column label="执行记录" min-width="180"><template #default="scope"><div v-for="attempt in scope.row.attempts" :key="attempt.attempt">第 {{ attempt.attempt }} 次：{{ attempt.state }} {{ attempt.errorMessage || '' }}</div></template></el-table-column><el-table-column label="操作"><template #default="scope"><el-popconfirm v-if="['FAILED','INTERRUPTED'].includes(scope.row.state)" title="重试可能重复写入，确认已检查幂等策略？" @confirm="retryTaskRun(scope.row)"><template #reference><el-button text type="warning">重试</el-button></template></el-popconfirm></template></el-table-column></el-table><el-pagination v-model:current-page="taskRunPaging.page.value" :page-size="taskRunPaging.pageSize.value" :total="taskRunPaging.total.value" layout="total, prev, pager, next"/></el-dialog>
  <el-dialog v-model="historyDialog" :title="`${historyWorkflow?.name || ''} 运行历史`" width="min(1000px, 96vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><div class="row"><el-select v-model="historyState" clearable placeholder="全部状态"><el-option v-for="state in ['PENDING','RUNNING','WAITING','SUCCEEDED','FAILED','INTERRUPTED']" :key="state" :label="state" :value="state"/></el-select><el-date-picker v-model="historyDate" type="date" value-format="YYYY-MM-DD" placeholder="业务日期"/><el-button @click="loadHistory(null)">查询</el-button></div><el-table :data="historyRuns"><el-table-column prop="id" label="实例"/><el-table-column prop="workflowVersion" label="版本"/><el-table-column prop="businessDate" label="业务日期"/><el-table-column prop="state" label="状态"/><el-table-column prop="createdAt" label="创建时间"/><el-table-column label="操作"><template #default="scope"><el-button text type="primary" @click="showRun(scope.row.id)">详情</el-button></template></el-table-column></el-table><el-button v-if="historyCanLoadMore" @click="loadHistory(null, true)">加载更多</el-button></el-dialog>
  <el-dialog v-model="runDialog" :title="`运行实例 ${run?.id || ''}`" width="min(850px, 95vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><div v-if="run"><div class="row"><strong>状态：{{ run.state }}</strong><el-button @click="refreshRun">刷新</el-button><el-popconfirm v-if="['FAILED','INTERRUPTED'].includes(run.state)" title="重试可能重复写入，确认已检查幂等策略？" @confirm="retryRun"><template #reference><el-button type="warning">重试失败节点</el-button></template></el-popconfirm></div><el-table :data="run.nodes"><el-table-column prop="key" label="节点标识"/><el-table-column prop="state" label="状态"/><el-table-column label="执行记录"><template #default="scope"><div v-for="a in scope.row.attempts" :key="a.attempt">第 {{ a.attempt }} 次：{{ a.state }} {{ a.errorMessage || '' }}</div></template></el-table-column></el-table></div></el-dialog>
  <el-dialog v-model="backfillDialog" title="按日期补跑" width="min(700px, 95vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><p>每次最多补跑 31 天。任务须配置业务日期参数，请确认数据范围和写入幂等策略。</p><div class="row"><el-select v-model="backfillWorkflow" placeholder="选择工作流"><el-option v-for="w in workflows" :key="w.id" :label="`${w.name}（版本 ${w.version}）`" :value="w.id"/></el-select><el-date-picker v-model="backfillDates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期"/><el-popconfirm title="补跑可能重复写入，确认已检查幂等策略？" @confirm="backfill"><template #reference><el-button type="primary" :loading="backfillBusy" :disabled="backfillBusy">创建补跑</el-button></template></el-popconfirm></div><div v-if="backfillRuns.length">运行实例：<el-button v-for="id in backfillRuns" :key="id" text @click="showRun(id)">{{ id }}</el-button></div></el-dialog>
  <el-dialog v-model="scheduleDialog" title="新建定时计划" width="min(650px, 95vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><p>使用六段 Cron 表达式。停用后重新启用从当前时间计算。</p><el-form label-position="top" @submit.prevent="createSchedule"><el-form-item label="工作流"><el-select v-model="scheduleForm.workflowId"><el-option v-for="w in workflows" :key="w.id" :label="`${w.name}（版本 ${w.version}）`" :value="w.id"/></el-select></el-form-item><el-form-item label="Cron（秒 分 时 日 月 周）"><el-input v-model="scheduleForm.cron"/></el-form-item><el-form-item label="时区"><el-input v-model="scheduleForm.zone"/></el-form-item><el-button type="primary" native-type="submit" :loading="scheduleBusy" :disabled="scheduleBusy">保存计划</el-button></el-form></el-dialog>
</section>
</template>
<style scoped>.pane{background:white;padding:22px;margin:16px 0;border:1px solid #e1eae8}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 0}.toolbar small{display:block;margin-top:5px;color:#728582}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.row{display:flex;gap:12px;align-items:center;margin:14px 0}.parameter-editor{padding:12px 0}.parameter-editor p{color:#687e7b;font-size:13px}.parameter-row .el-select,.parameter-row .el-input{max-width:200px}.node{display:flex;justify-content:space-between;align-items:center;padding:6px 10px;border-bottom:1px solid #e8efee}.el-pagination{margin-top:18px;justify-content:flex-end}@media(max-width:700px){.grid{grid-template-columns:1fr}.row,.toolbar{flex-wrap:wrap}}</style>
