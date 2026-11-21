<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { authorizedRequest } from './auth.js'
import { usePagination } from './pagination.js'
import { filterTasks, batchParts, flattenTree, flowDraftForEdit, flowDraftForCopy } from './processing-tasks.js'
import TaskFormDialog from './TaskFormDialog.vue'
import TaskRunsDialog from './TaskRunsDialog.vue'
import FlowEditor from './FlowEditor.vue'
import RefreshIcon from './RefreshIcon.vue'
const can = inject('canPermission', () => false)
const directories = ref([]), tasks = ref([]), sources = ref([]), error = ref(''), message = ref('')
const tree = ref(null), currentDirectory = ref(0)
const view = ref('list'), flowDraft = ref(null)
const nameSearch = ref(''), nameFilter = ref(''), stateSearch = ref(''), stateFilter = ref('')
const selected = ref([]), formOpen = ref(false), formTask = ref(null), formReadonly = ref(false), runsOpen = ref(false), runsTask = ref(null)
const startOpen = ref(false), startTargets = ref([]), startDate = ref(new Date().toLocaleDateString('sv-SE')), batchBusy = ref(false)
const filtered = computed(() => filterTasks(tasks.value, { directories: directories.value, directoryId: currentDirectory.value === 0 ? null : currentDirectory.value, name: nameFilter.value, runState: stateFilter.value }))
const paging = usePagination(filtered)
const currentDirectoryName = computed(() => currentDirectory.value === 0 ? '全部任务' : directories.value.find(d => d.id === currentDirectory.value)?.name ?? '全部任务')
// 窄屏顶部目录下拉：先序展开 + 全角空格缩进层级
const directoryOptions = computed(() => {
  const walk = (parentId, depth) => directories.value.filter(d => d.parentId === parentId).sort((a,b)=>a.sortOrder-b.sortOrder).flatMap(d => [{ id: d.id, label: '　'.repeat(depth) + d.name }, ...walk(d.id, depth+1)])
  return [{ id: 0, label: '全部任务' }, ...walk(null, 0)]
})
const treeData = computed(() => {
  const build = parentId => directories.value.filter(d => d.parentId === parentId).sort((a, b) => a.sortOrder - b.sortOrder).map(d => ({ id: d.id, name: d.name, children: build(d.id) }))
  return [{ id: 0, name: '全部任务', children: build(null) }]
})
async function get(url) { const response = await fetch(url, { credentials: 'same-origin' }); if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有相应权限' : '读取失败，请检查连接'); return response.json() }
const runStateLabels = { SUCCEEDED: '成功', FAILED: '失败', INTERRUPTED: '已中断', RUNNING: '运行中', PENDING: '排队', WAITING: '等待' }
function runStateText(state) { return runStateLabels[state] ?? state }
function fmtRunTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '' }
// 序号守卫：丢弃过期的 loadTasks 响应，避免轮询旧结果覆盖新数据
let taskLoadSequence = 0
async function loadAll() {
  error.value = ''
  try {
    const [directoryRows, taskRows, sourceRows] = await Promise.all([
      can('workflow.task.read') ? get('/api/task-directories') : Promise.resolve([]),
      can('workflow.task.read') ? get('/api/tasks') : Promise.resolve([]),
      can('datasource.source.read') ? get('/api/data-sources') : Promise.resolve([])
    ])
    taskLoadSequence++ // 作废仍在途的 loadTasks 响应
    directories.value = directoryRows; tasks.value = taskRows; sources.value = sourceRows
  } catch (e) { error.value = e.message }
}
async function loadTasks() {
  error.value = ''
  const sequence = ++taskLoadSequence
  try {
    const taskRows = can('workflow.task.read') ? await get('/api/tasks') : []
    if (sequence === taskLoadSequence) tasks.value = taskRows
  } catch (e) { if (sequence === taskLoadSequence) error.value = e.message }
}
// 批量结果分流：先刷新（loadAll 会清 error）再写提示，避免失败文案被刷新覆盖
function applyBatchParts(results) {
  const { okText, failText } = batchParts(results)
  message.value = okText || (failText ? '' : '没有可操作的任务')
  if (failText) error.value = failText
}
function applyQuery() { nameFilter.value = nameSearch.value.trim(); stateFilter.value = stateSearch.value; paging.page.value = 1 }
function selectDirectory(node) { currentDirectory.value = node.id; paging.page.value = 1 }
async function submitSort() {
  // 拖拽只改 el-tree 内部节点结构，须从 getNode 读取；根哨兵节点的子节点即顶级目录
  const toPlain = node => ({ id: node.data.id, children: node.childNodes.map(toPlain) })
  const items = flattenTree(tree.value.getNode(0).childNodes.map(toPlain))
  try {
    const response = await authorizedRequest('/api/task-directories/sort', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ items }) })
    if (!response.ok) throw new Error(response.status === 409 ? '目录不能移入自身子孙' : response.status === 403 ? '当前账号没有目录修改权限' : '目录排序保存失败')
    await loadAll()
  } catch (e) { await loadAll(); error.value = e.message } // loadAll 会清 error，刷新后再写失败文案
}
async function addDirectory(parent) {
  error.value = ''; message.value = ''
  try {
    const { value } = await ElMessageBox.prompt('请输入目录名称', parent ? '新增子目录' : '新增目录', { confirmButtonText: '保存', cancelButtonText: '取消', inputPattern: /\S+/, inputErrorMessage: '名称不能为空' })
    const response = await authorizedRequest('/api/task-directories', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: value, parentId: parent?.id ?? null }) })
    if (!response.ok) throw new Error('目录创建失败')
    message.value = '目录已创建'; await loadAll()
  } catch (e) { if (e !== 'cancel' && e?.message !== 'cancel') error.value = e.message || String(e) }
}
async function renameDirectory(node) {
  error.value = ''; message.value = ''
  try {
    const { value } = await ElMessageBox.prompt('请输入新名称', '重命名目录', { confirmButtonText: '保存', cancelButtonText: '取消', inputValue: node.name, inputPattern: /\S+/, inputErrorMessage: '名称不能为空' })
    const response = await authorizedRequest(`/api/task-directories/${node.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: value }) })
    if (!response.ok) throw new Error('目录重命名失败')
    await loadAll()
  } catch (e) { if (e !== 'cancel' && e?.message !== 'cancel') error.value = e.message || String(e) }
}
async function deleteDirectory(node) {
  error.value = ''; message.value = ''
  try {
    await ElMessageBox.confirm(`确定删除目录“${node.name}”？含任务或子目录时无法删除。`, '删除目录', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    const response = await authorizedRequest(`/api/task-directories/${node.id}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(response.status === 409 ? '目录包含子目录或任务，不能删除' : '目录删除失败')
    if (currentDirectory.value === node.id) currentDirectory.value = 0
    message.value = '目录已删除'; await loadAll()
  } catch (e) { if (e !== 'cancel' && e?.message !== 'cancel') error.value = e.message || String(e) }
}
function openCreate() { formTask.value = null; formReadonly.value = false; formOpen.value = true }
function openFlow(metadata) { error.value = ''; message.value = ''; selected.value = []; flowDraft.value = { ...metadata, id: null }; view.value = 'editor' }
function openFlowEdit(row) { error.value = ''; message.value = ''; selected.value = []; flowDraft.value = flowDraftForEdit(row); view.value = 'editor' }
function flowSaved() { error.value = ''; view.value = 'list'; flowDraft.value = null; message.value = '流程已保存'; loadAll() }
function flowCancel() { error.value = ''; message.value = ''; view.value = 'list'; flowDraft.value = null }
function openEdit(row) { if (row.type === 'FLOW') { openFlowEdit(row); return } formTask.value = row; formReadonly.value = row.runState === 'RUNNING'; formOpen.value = true }
function openCopy(row) { if (row.type === 'FLOW') { openFlow(flowDraftForCopy(row)); return } formTask.value = { ...row, id: null, name: `${row.name}-副本` }; formReadonly.value = false; formOpen.value = true }
function openRuns(row) { runsTask.value = row; runsOpen.value = true }
function openStart(targets) { startTargets.value = targets; startDate.value = new Date().toLocaleDateString('sv-SE'); startOpen.value = true }
async function confirmStart() {
  if (batchBusy.value) return
  batchBusy.value = true; error.value = ''; message.value = ''
  // 弹窗期间列表可能已轮询刷新，按 id 重新解析最新快照，已运行的直接记失败不重复 POST
  const targets = startTargets.value.map(t => tasks.value.find(x => x.id === t.id)).filter(Boolean)
  const results = []
  for (const target of targets) {
    if (target.runState === 'RUNNING') { results.push({ name: target.name, ok: false, error: '任务已在运行' }); continue }
    try {
      const response = await authorizedRequest(`/api/tasks/${target.id}/runs`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ version: target.version, businessDate: startDate.value }) })
      if (!response.ok) throw new Error(response.status === 409 ? '任务已在运行' : response.status === 403 ? '没有运行权限' : '启动失败')
      results.push({ name: target.name, ok: true })
    } catch (e) { results.push({ name: target.name, ok: false, error: e.message }) }
  }
  batchBusy.value = false; startOpen.value = false
  await loadAll()
  applyBatchParts(results)
}
async function stopOne(row) {
  error.value = ''; message.value = ''
  try {
    const response = await authorizedRequest(`/api/tasks/${row.id}/stop`, { method: 'POST' })
    if (!response.ok) throw new Error(response.status === 409 ? '任务未在运行' : '停止失败')
    message.value = `已停止 ${row.name}`; await loadAll()
  } catch (e) { error.value = e.message }
}
async function stopBatch() {
  try { await ElMessageBox.confirm(`确定停止选中的 ${selected.value.length} 个任务？停止会中断运行，可能已写入部分数据。`, '批量停止', { confirmButtonText: '停止', cancelButtonText: '取消', type: 'warning' }) } catch { return }
  error.value = ''; message.value = ''; batchBusy.value = true
  // 确认框期间列表可能已轮询刷新，按 id 重新解析最新快照
  const targets = selected.value.map(t => tasks.value.find(x => x.id === t.id)).filter(Boolean)
  const results = []
  try {
    for (const target of targets) {
      try {
        const response = await authorizedRequest(`/api/tasks/${target.id}/stop`, { method: 'POST' })
        if (!response.ok) throw new Error(response.status === 409 ? '任务未在运行' : '停止失败')
        results.push({ name: target.name, ok: true })
      } catch (e) { results.push({ name: target.name, ok: false, error: e.message }) }
    }
  } finally { batchBusy.value = false }
  await loadAll()
  applyBatchParts(results)
}
async function removeTask(row) {
  error.value = ''; message.value = ''
  try {
    const response = await authorizedRequest(`/api/tasks/${row.id}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(response.status === 409 ? '任务运行中或被工作流引用，不能删除' : '删除失败')
    message.value = `已删除 ${row.name}`; await loadAll()
  } catch (e) { error.value = e.message }
}
let pollTimer = null
watch(tasks, rows => {
  const running = rows.some(task => task.runState === 'RUNNING')
  if (running && !pollTimer) pollTimer = setInterval(loadTasks, 5000)
  if (!running && pollTimer) { clearInterval(pollTimer); pollTimer = null }
}, { deep: false })
// 选中目录被删除/重载后不存在时回退到全部任务
watch(directories, rows => {
  if (currentDirectory.value !== 0 && !rows.some(d => d.id === currentDirectory.value)) currentDirectory.value = 0
})
// 树点选与窄屏下拉统一在此重置页码
watch(currentDirectory, () => { paging.page.value = 1 })
onMounted(loadAll)
onBeforeUnmount(() => { if (pollTimer) clearInterval(pollTimer) })
</script>
<template>
  <section class="processing">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <el-alert v-if="message" :title="message" type="success" :closable="false" show-icon/>
    <template v-if="view === 'list'">
      <el-select v-model="currentDirectory" class="directory-select" aria-label="任务目录"><el-option v-for="o in directoryOptions" :key="o.id" :label="o.label" :value="o.id"/></el-select>
      <div class="processing-layout">
        <aside class="directory-pane">
          <div class="directory-head"><strong>任务目录</strong><el-button v-if="can('workflow.task.create')" text type="primary" @click="addDirectory(null)">新增</el-button></div>
          <el-tree ref="tree" :data="treeData" node-key="id" :props="{ label: 'name', children: 'children' }" default-expand-all :draggable="can('workflow.task.update')" :allow-drag="node => node.data.id !== 0" :allow-drop="(draggingNode, dropNode, type) => dropNode.data.id !== 0 || type === 'inner'" @node-click="selectDirectory" @node-drop="submitSort">
            <template #default="{ data }">
              <span class="tree-node">
                <span>{{ data.name }}</span>
                <span v-if="data.id !== 0" class="tree-actions">
                  <el-button v-if="can('workflow.task.create')" text size="small" @click.stop="addDirectory(data)">新增</el-button>
                  <el-button v-if="can('workflow.task.update')" text size="small" @click.stop="renameDirectory(data)">重命名</el-button>
                  <el-button v-if="can('workflow.task.delete')" text size="small" type="danger" @click.stop="deleteDirectory(data)">删除</el-button>
                </span>
              </span>
            </template>
          </el-tree>
        </aside>
        <div class="list-pane">
          <div class="toolbar list-query-toolbar">
            <div class="list-query-controls">
              <el-input v-model="nameSearch" clearable placeholder="搜索任务名称" @keyup.enter="applyQuery"/>
              <el-select v-model="stateSearch" clearable placeholder="运行状态" style="width:130px"><el-option label="运行中" value="RUNNING"/><el-option label="停止" value="STOPPED"/></el-select>
              <el-button type="primary" @click="applyQuery">查询</el-button>
              <span>当前目录：{{ currentDirectoryName }}</span>
            </div>
            <div class="list-query-actions">
              <el-button v-if="can('workflow.task.create') && can('datasource.source.read')" type="primary" @click="openCreate">新增</el-button>
              <el-button v-if="can('workflow.task.execute')" :disabled="!selected.length || batchBusy" @click="openStart(selected)">批量启动</el-button>
              <el-button v-if="can('workflow.task.execute')" :disabled="!selected.length || batchBusy" @click="stopBatch">批量停止</el-button>
              <el-button class="refresh-button" title="刷新" aria-label="刷新" @click="loadAll"><RefreshIcon /></el-button>
            </div>
          </div>
          <el-table :data="paging.visible.value" empty-text="暂无任务" row-key="id" @selection-change="selected = $event">
            <el-table-column v-if="can('workflow.task.execute')" type="selection" width="45"/>
            <el-table-column type="index" label="序号" width="60"/>
            <el-table-column prop="name" label="任务名称" min-width="160"/>
            <el-table-column label="类型" width="110"><template #default="scope">{{ scope.row.type === 'SQL' ? 'SQL 加工' : scope.row.type === 'SYNC' ? '数据库同步' : scope.row.type === 'FLOW' ? '流程编排' : scope.row.type }}</template></el-table-column>
            <el-table-column prop="version" label="版本" width="70"/>
            <el-table-column label="目录" min-width="110"><template #default="scope">{{ directories.find(d => d.id === scope.row.directoryId)?.name ?? '未分类' }}</template></el-table-column>
            <el-table-column label="运行状态" width="90"><template #default="scope"><el-tag :type="scope.row.runState === 'RUNNING' ? 'success' : 'info'" size="small">{{ scope.row.runState === 'RUNNING' ? '运行中' : '停止' }}</el-tag></template></el-table-column>
            <el-table-column label="最近运行" min-width="170"><template #default="scope"><span v-if="scope.row.lastRun">{{ runStateText(scope.row.lastRun.state) }} · {{ fmtRunTime(scope.row.lastRun.finishedAt || scope.row.lastRun.startedAt) }}</span><span v-else class="status-text">未运行</span></template></el-table-column>
            <el-table-column label="操作" min-width="280" fixed="right"><template #default="scope">
              <el-button v-if="can('workflow.task.execute') && scope.row.runState !== 'RUNNING'" text type="primary" @click="openStart([scope.row])">启动</el-button>
              <el-popconfirm v-if="can('workflow.task.execute') && scope.row.runState === 'RUNNING'" title="停止会中断运行，可能已写入部分数据，确定停止？" @confirm="stopOne(scope.row)"><template #reference><el-button text type="warning">停止</el-button></template></el-popconfirm>
              <el-button v-if="can('workflow.task.update') || scope.row.runState === 'RUNNING'" text @click="openEdit(scope.row)">{{ scope.row.runState === 'RUNNING' ? '查看' : '编辑' }}</el-button>
              <el-button v-if="can('workflow.task.create')" text @click="openCopy(scope.row)">复制</el-button>
              <el-popconfirm v-if="can('workflow.task.delete')" :title="`确定删除任务 ${scope.row.name}？运行历史将一并清除。`" @confirm="removeTask(scope.row)"><template #reference><el-button text type="danger" :disabled="scope.row.runState === 'RUNNING'">删除</el-button></template></el-popconfirm>
              <el-button v-if="can('workflow.task.read')" text @click="openRuns(scope.row)">运行记录</el-button>
            </template></el-table-column>
          </el-table>
          <div class="pagination"><el-pagination v-model:current-page="paging.page.value" v-model:page-size="paging.pageSize.value" :total="paging.total.value" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next"/></div>
        </div>
      </div>
      <TaskFormDialog v-model:open="formOpen" :task="formTask" :sources="sources" :directories="directories" :readonly="formReadonly" @saved="message = '任务已保存'; loadAll()" @flow="openFlow"/>
      <TaskRunsDialog v-model:open="runsOpen" :task="runsTask"/>
      <el-dialog v-model="startOpen" :title="`启动 ${startTargets.length} 个任务`" width="min(480px, 95vw)">
        <el-form label-position="top"><el-form-item label="业务日期"><el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD" :clearable="false"/></el-form-item></el-form>
        <p v-for="target in startTargets" :key="target.id" class="status-text">{{ target.name }}（版本 {{ target.version }}）</p>
        <template #footer><el-button :disabled="batchBusy" @click="startOpen = false">取消</el-button><el-button type="primary" :loading="batchBusy" @click="confirmStart">启动</el-button></template>
      </el-dialog>
    </template>
    <FlowEditor v-else :draft="flowDraft" :sources="sources" :readonly="flowDraft?.runState === 'RUNNING'" @saved="flowSaved" @cancel="flowCancel"/>
  </section>
</template>
<style scoped>
.processing-layout{display:flex;gap:14px;align-items:flex-start}
.directory-pane{flex:0 0 220px;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:4px;padding:12px}
.directory-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}
.list-pane{flex:1;min-width:0}
.tree-node{display:flex;justify-content:space-between;align-items:center;gap:6px;flex:1;min-width:0}
.tree-actions{display:none;white-space:nowrap}
.el-tree-node__content:hover .tree-actions{display:inline-flex}
.el-tree-node__content:focus-within .tree-actions{display:inline-flex}
@media(hover:none){.tree-actions{display:inline-flex}}
.status-text{color:#728582;font-size:12px}
.pagination{margin-top:14px;display:flex;justify-content:flex-end}
.directory-select{display:none}
@media(max-width:700px){.directory-pane{display:none}.directory-select{display:block;width:100%;margin-bottom:10px}}
</style>
