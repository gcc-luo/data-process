<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authorizedRequest } from './auth.js'
import { usePagination } from './pagination.js'
import { filterTasks, batchParts, flattenTree } from './processing-tasks.js'
import * as api from './orch-api.js'
import OrchestratorEditor from './OrchestratorEditor.vue'
import OrchRunsDialog from './OrchRunsDialog.vue'
import RefreshIcon from './RefreshIcon.vue'
import DirectoryPanelHeader from './DirectoryPanelHeader.vue'
const can = inject('canPermission', () => false)
const directories = ref([]), tasks = ref([]), sources = ref([]), error = ref('')
const tree = ref(null), currentDirectory = ref(0), directoryMenu = ref(null)
const directorySearch = ref('')
watch([directorySearch, directories, tree], async () => { await nextTick(); tree.value?.filter(directorySearch.value.trim()) })
function filterDirectory(value, data) { return !value || data.name.toLowerCase().includes(value.toLowerCase()) }
const view = ref('list'), editorTaskId = ref(null), editorRef = ref(null)
const nameSearch = ref(''), nameFilter = ref(''), stateSearch = ref(''), stateFilter = ref('')
const selected = ref([]), runsOpen = ref(false), runsTask = ref(null)
const createOpen = ref(false), createBusy = ref(false), createForm = ref({ name: '', directoryId: null, description: '' })
const startOpen = ref(false), startTargets = ref([]), startDate = ref(new Date().toLocaleDateString('sv-SE')), batchBusy = ref(false)
const filtered = computed(() => filterTasks(tasks.value, { directories: directories.value, directoryId: currentDirectory.value === 0 ? null : currentDirectory.value, name: nameFilter.value, runState: stateFilter.value }))
const paging = usePagination(filtered)
const directoryOptions = computed(() => {
  const walk = (parentId, depth) => directories.value.filter(d => d.parentId === parentId).sort((a, b) => a.sortOrder - b.sortOrder).flatMap(d => [{ id: d.id, label: '　'.repeat(depth) + d.name }, ...walk(d.id, depth + 1)])
  return [{ id: 0, label: '任务目录' }, ...walk(null, 0)]
})
const treeData = computed(() => {
  const build = parentId => directories.value.filter(d => d.parentId === parentId).sort((a, b) => a.sortOrder - b.sortOrder).map(d => ({ id: d.id, name: d.name, children: build(d.id) }))
  return [{ id: 0, name: '任务目录', children: build(null) }]
})
async function get(url) { const response = await fetch(url, { credentials: 'same-origin' }); if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有相应权限' : '读取失败，请检查连接'); return response.json() }
const runStateLabels = { SUCCEEDED: '成功', FAILED: '失败', INTERRUPTED: '已中断', RUNNING: '运行中', PENDING: '排队', WAITING: '等待' }
function runStateText(state) { return runStateLabels[state] ?? state }
function fmtRunTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '' }
let taskLoadSequence = 0
async function loadAll() {
  error.value = ''
  try {
    const [directoryRows, taskRows, sourceRows] = await Promise.all([
      can('workflow.task.read') ? get('/api/task-directories') : Promise.resolve([]),
      can('workflow.task.read') ? api.fetchTasks() : Promise.resolve([]),
      can('datasource.source.read') ? get('/api/data-sources') : Promise.resolve([])
    ])
    taskLoadSequence++
    directories.value = directoryRows; tasks.value = taskRows; sources.value = sourceRows
  } catch (e) { error.value = e.message }
}
async function loadTasks() {
  error.value = ''
  const sequence = ++taskLoadSequence
  try {
    const taskRows = can('workflow.task.read') ? await api.fetchTasks() : []
    if (sequence === taskLoadSequence) tasks.value = taskRows
  } catch (e) { if (sequence === taskLoadSequence) error.value = e.message }
}
function applyBatchParts(results) {
  const { okText, failText } = batchParts(results)
  // 成功反馈用顶部居中 toast，不再占用列表上方布局（视觉伴侣方案 A）
  if (okText) ElMessage.success(okText)
  else if (!failText) ElMessage.warning('没有可操作的任务')
  if (failText) error.value = failText
}
function applyQuery() { nameFilter.value = nameSearch.value.trim(); stateFilter.value = stateSearch.value; paging.page.value = 1 }
function closeDirectoryMenu() { directoryMenu.value = null }
function selectDirectory(node) { currentDirectory.value = node.id; paging.page.value = 1; closeDirectoryMenu() }
function openDirectoryMenu(event, node) {
  event.preventDefault(); event.stopPropagation(); selectDirectory(node)
  const allowed = node.id === 0 ? can('workflow.task.create') : can('workflow.task.create') || can('workflow.task.update') || can('workflow.task.delete')
  if (!allowed) return
  const itemCount = node.id === 0 ? 1 : Number(can('workflow.task.create')) + Number(can('workflow.task.update')) + Number(can('workflow.task.delete'))
  const hasDivider = node.id !== 0 && can('workflow.task.create') && (can('workflow.task.update') || can('workflow.task.delete'))
  const menuHeight = itemCount * 34 + (hasDivider ? 21 : 10), menuWidth = 120
  const fromPointer = event.type === 'contextmenu' && (event.clientX || event.clientY)
  const rowRect = !fromPointer && event.type === 'contextmenu' && event.target instanceof Element ? event.target.closest('.el-tree-node__content')?.getBoundingClientRect() : null
  const rect = rowRect ?? event.currentTarget?.getBoundingClientRect?.()
  const requestedX = fromPointer ? event.clientX : Math.max(8, (rect?.right ?? 200) - menuWidth)
  const requestedY = fromPointer ? event.clientY : (rect?.bottom ?? 40) + 4
  directoryMenu.value = { x: Math.max(8, Math.min(requestedX, window.innerWidth - menuWidth - 8)), y: Math.max(8, Math.min(requestedY, window.innerHeight - menuHeight - 8)), node }
}
function runDirectoryMenuAction(action) {
  const node = directoryMenu.value?.node
  closeDirectoryMenu()
  if (action === 'create') addDirectory(null)
  else if (action === 'create-child' && node) addDirectory(node)
  else if (action === 'rename' && node) renameDirectory(node)
  else if (action === 'delete' && node) deleteDirectory(node)
}
function handleMenuKeydown(event) { if (event.key === 'Escape') closeDirectoryMenu() }
function handleOutsidePointerdown(event) { if (event.target instanceof Element && event.target.closest('.directory-context-menu, .directory-menu-trigger')) return; closeDirectoryMenu() }
function closeMenuOnViewportChange() { closeDirectoryMenu() }
async function submitSort() {
  const toPlain = node => ({ id: node.data.id, children: node.childNodes.map(toPlain) })
  const items = flattenTree(tree.value.getNode(0).childNodes.map(toPlain))
  try {
    const response = await authorizedRequest('/api/task-directories/sort', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ items }) })
    if (!response.ok) throw new Error(response.status === 409 ? '目录不能移入自身子孙' : response.status === 403 ? '当前账号没有目录修改权限' : '目录排序保存失败')
    await loadAll()
  } catch (e) { await loadAll(); error.value = e.message }
}
async function addDirectory(parent) {
  error.value = ''
  try {
    const { value } = await ElMessageBox.prompt('请输入目录名称', parent ? '新增子目录' : '新增目录', { confirmButtonText: '保存', cancelButtonText: '取消', inputPattern: /\S+/, inputErrorMessage: '名称不能为空' })
    const response = await authorizedRequest('/api/task-directories', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: value, parentId: parent?.id ?? null }) })
    if (!response.ok) throw new Error('目录创建失败')
    ElMessage.success('目录已创建'); await loadAll()
  } catch (e) { if (e !== 'cancel' && e?.message !== 'cancel') error.value = e.message || String(e) }
}
async function renameDirectory(node) {
  error.value = ''
  try {
    const { value } = await ElMessageBox.prompt('请输入新名称', '重命名目录', { confirmButtonText: '保存', cancelButtonText: '取消', inputValue: node.name, inputPattern: /\S+/, inputErrorMessage: '名称不能为空' })
    const response = await authorizedRequest(`/api/task-directories/${node.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: value }) })
    if (!response.ok) throw new Error('目录重命名失败')
    await loadAll()
  } catch (e) { if (e !== 'cancel' && e?.message !== 'cancel') error.value = e.message || String(e) }
}
async function deleteDirectory(node) {
  error.value = ''
  try {
    await ElMessageBox.confirm(`确定删除目录“${node.name}”？含任务或子目录时无法删除。`, '删除目录', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    const response = await authorizedRequest(`/api/task-directories/${node.id}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(response.status === 409 ? '目录包含子目录或任务，不能删除' : '目录删除失败')
    if (currentDirectory.value === node.id) currentDirectory.value = 0
    ElMessage.success('目录已删除'); await loadAll()
  } catch (e) { if (e !== 'cancel' && e?.message !== 'cancel') error.value = e.message || String(e) }
}
// ---- task CRUD ----
function openCreate() { createForm.value = { name: '', directoryId: currentDirectory.value === 0 ? null : currentDirectory.value, description: '' }; createOpen.value = true }
async function confirmCreate() {
  if (!createForm.value.name.trim()) { ElMessage.warning('请填写任务名称'); return }
  createBusy.value = true
  try {
    const res = await api.createTask({ name: createForm.value.name.trim(), directoryId: createForm.value.directoryId, description: createForm.value.description, template: 'BLANK' })
    createOpen.value = false
    editorTaskId.value = res.id
    view.value = 'editor'
    await loadAll()
  } catch (e) { ElMessage.error(e.message) } finally { createBusy.value = false }
}
function openEdit(row) { error.value = ''; editorTaskId.value = row.id; view.value = 'editor' }
async function openCopy(row) {
  try {
    const res = await api.copyTask(row.id, { name: `${row.name}-副本`, directoryId: row.directoryId })
    editorTaskId.value = res.id; view.value = 'editor'; await loadAll()
  } catch (e) { ElMessage.error(e.message) }
}
function editorBack() { view.value = 'list'; editorTaskId.value = null; loadAll() }
function editorSaved() { loadTasks() }
function openRuns(row) { runsTask.value = row; runsOpen.value = true }
function openStart(targets) { startTargets.value = targets; startDate.value = new Date().toLocaleDateString('sv-SE'); startOpen.value = true }
async function confirmStart() {
  if (batchBusy.value) return
  batchBusy.value = true; error.value = ''
  const targets = startTargets.value.map(t => tasks.value.find(x => x.id === t.id)).filter(Boolean)
  const results = []
  for (const target of targets) {
    if (target.runState === 'RUNNING') { results.push({ name: target.name, ok: false, error: '任务已在运行' }); continue }
    if (target.version < 1) { results.push({ name: target.name, ok: false, error: '尚未发布' }); continue }
    try { await api.startRun(target.id, { businessDate: startDate.value }); results.push({ name: target.name, ok: true }) }
    catch (e) { results.push({ name: target.name, ok: false, error: e.message }) }
  }
  batchBusy.value = false; startOpen.value = false
  await loadAll(); applyBatchParts(results)
}
async function stopOne(row) {
  error.value = ''
  try { await api.stopTask(row.id); ElMessage.success(`已停止 ${row.name}`); await loadAll() } catch (e) { error.value = e.message }
}
async function stopBatch() {
  try { await ElMessageBox.confirm(`确定停止选中的 ${selected.value.length} 个任务？停止会中断运行，可能已写入部分数据。`, '批量停止', { confirmButtonText: '停止', cancelButtonText: '取消', type: 'warning' }) } catch { return }
  error.value = ''; batchBusy.value = true
  const targets = selected.value.map(t => tasks.value.find(x => x.id === t.id)).filter(Boolean)
  const results = []
  try {
    for (const target of targets) {
      try { await api.stopTask(target.id); results.push({ name: target.name, ok: true }) }
      catch (e) { results.push({ name: target.name, ok: false, error: e.message }) }
    }
  } finally { batchBusy.value = false }
  await loadAll(); applyBatchParts(results)
}
async function removeTask(row) {
  error.value = ''
  try { await api.deleteTask(row.id); ElMessage.success(`已删除 ${row.name}`); await loadAll() } catch (e) { error.value = e.message }
}
let pollTimer = null
watch(tasks, rows => {
  const running = rows.some(task => task.runState === 'RUNNING')
  if (running && !pollTimer) pollTimer = setInterval(loadTasks, 5000)
  if (!running && pollTimer) { clearInterval(pollTimer); pollTimer = null }
}, { deep: false })
watch(directories, rows => { if (currentDirectory.value !== 0 && !rows.some(d => d.id === currentDirectory.value)) currentDirectory.value = 0 })
watch(currentDirectory, () => { paging.page.value = 1 })
function hasUnsaved() { return view.value === 'editor' && editorRef.value?.hasUnsaved?.() }
defineExpose({ hasUnsaved })
onMounted(() => {
  loadAll()
  window.addEventListener('pointerdown', handleOutsidePointerdown)
  window.addEventListener('keydown', handleMenuKeydown)
  window.addEventListener('resize', closeMenuOnViewportChange)
  window.addEventListener('scroll', closeMenuOnViewportChange, true)
})
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
  window.removeEventListener('pointerdown', handleOutsidePointerdown)
  window.removeEventListener('keydown', handleMenuKeydown)
  window.removeEventListener('resize', closeMenuOnViewportChange)
  window.removeEventListener('scroll', closeMenuOnViewportChange, true)
})
</script>
<template>
  <section class="processing" :class="{ 'processing-has-directory': view === 'list' }">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <template v-if="view === 'list'">
      <el-select v-model="currentDirectory" filterable class="directory-select" aria-label="任务目录"><el-option v-for="o in directoryOptions" :key="o.id" :label="o.label" :value="o.id"/></el-select>
      <div class="processing-layout">
        <aside class="directory-pane catalog-panel">
          <DirectoryPanelHeader v-model="directorySearch" title="任务目录"/>
          <el-tree class="directory-tree" ref="tree" :filter-node-method="filterDirectory" highlight-current :current-node-key="currentDirectory" :data="treeData" node-key="id" :props="{ label: 'name', children: 'children' }" default-expand-all :draggable="can('workflow.task.update')" :allow-drag="node => node.data.id !== 0" :allow-drop="(draggingNode, dropNode, type) => dropNode.data.id !== 0 || type === 'inner'" @node-click="selectDirectory" @node-contextmenu="openDirectoryMenu" @node-drop="submitSort">
            <template #default="{ data }">
              <span class="tree-node">
                <svg v-if="data.id !== 0" class="directory-folder-icon" viewBox="0 0 16 14" aria-hidden="true"><path d="M1 2.5c0-.8.7-1.5 1.5-1.5h3l1.6 1.7h6.4c.8 0 1.5.7 1.5 1.5v7.1c0 .9-.7 1.6-1.6 1.6H2.6c-.9 0-1.6-.7-1.6-1.6Z"/><path class="folder-highlight" d="M1.4 4.1h13"/></svg>
                <span class="tree-label">{{ data.name }}</span>
                <button v-if="data.id === 0 ? can('workflow.task.create') : can('workflow.task.create') || can('workflow.task.update') || can('workflow.task.delete')" type="button" class="directory-menu-trigger" :aria-label="`${data.name}目录操作`" aria-haspopup="menu" @pointerdown.stop @click.stop="openDirectoryMenu($event, data)" @contextmenu.stop.prevent="openDirectoryMenu($event, data)">⋯</button>
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
            </div>
            <div class="list-query-actions">
              <el-button v-if="can('workflow.task.create') && can('datasource.source.read')" type="primary" @click="openCreate">新增</el-button>
              <el-button class="refresh-button" title="刷新" aria-label="刷新" @click="loadAll"><RefreshIcon /></el-button>
            </div>
          </div>
          <el-table class="task-table" :data="paging.visible.value" empty-text="暂无任务" row-key="id" @selection-change="selected = $event">
            <el-table-column type="selection" width="42"/>
            <el-table-column type="index" label="序号" width="60"/>
            <el-table-column prop="name" label="任务名称" min-width="160"/>
            <el-table-column label="状态" width="130"><template #default="scope"><el-tag size="small" :type="scope.row.version > 0 ? 'success' : 'info'">{{ scope.row.version > 0 ? `已发布 v${scope.row.version}` : '草稿' }}</el-tag></template></el-table-column>
            <el-table-column label="目录" min-width="110"><template #default="scope">{{ directories.find(d => d.id === scope.row.directoryId)?.name ?? '未分类' }}</template></el-table-column>
            <el-table-column label="运行状态" width="90"><template #default="scope"><el-tag :type="scope.row.runState === 'RUNNING' ? 'success' : 'info'" size="small">{{ scope.row.runState === 'RUNNING' ? '运行中' : '停止' }}</el-tag></template></el-table-column>
            <el-table-column label="最近运行" min-width="200" class-name="last-run-column"><template #default="scope"><span v-if="scope.row.lastRun">{{ runStateText(scope.row.lastRun.state) }} · {{ fmtRunTime(scope.row.lastRun.finishedAt || scope.row.lastRun.startedAt) }}</span><span v-else class="status-text">未运行</span></template></el-table-column>
            <el-table-column label="操作" min-width="300" fixed="right" class-name="op-column"><template #default="scope">
              <el-button v-if="can('workflow.task.execute') && scope.row.runState !== 'RUNNING'" text type="primary" @click="openStart([scope.row])">启动</el-button>
              <el-popconfirm v-if="can('workflow.task.execute') && scope.row.runState === 'RUNNING'" title="停止会中断运行，可能已写入部分数据，确定停止？" @confirm="stopOne(scope.row)"><template #reference><el-button text type="warning">停止</el-button></template></el-popconfirm>
              <el-button v-if="can('workflow.task.update') || scope.row.runState === 'RUNNING'" text type="primary" @click="openEdit(scope.row)">{{ scope.row.runState === 'RUNNING' ? '查看' : '编辑' }}</el-button>
              <el-button v-if="can('workflow.task.create')" text type="primary" @click="openCopy(scope.row)">复制</el-button>
              <el-popconfirm v-if="can('workflow.task.delete')" :title="`确定删除任务 ${scope.row.name}？运行历史将一并清除。`" @confirm="removeTask(scope.row)"><template #reference><el-button text type="danger" :disabled="scope.row.runState === 'RUNNING'">删除</el-button></template></el-popconfirm>
              <el-button v-if="can('workflow.task.read')" text type="primary" @click="openRuns(scope.row)">运行记录</el-button>
            </template></el-table-column>
          </el-table>
          <div class="pagination"><el-pagination v-model:current-page="paging.page.value" v-model:page-size="paging.pageSize.value" :total="paging.total.value" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next"/></div>
        </div>
      </div>
      <OrchRunsDialog v-model:open="runsOpen" :task="runsTask" @changed="loadAll"/>
      <el-dialog v-model="createOpen" title="新增编排任务" width="min(520px,95vw)">
        <el-form label-position="top">
          <el-form-item label="任务名称" required><el-input v-model="createForm.name" maxlength="160" placeholder="例如：订单增量同步"/></el-form-item>
          <el-form-item label="所属目录"><el-select v-model="createForm.directoryId" clearable placeholder="未分类"><el-option v-for="o in directoryOptions.filter(x => x.id !== 0)" :key="o.id" :label="o.label" :value="o.id"/></el-select></el-form-item>
          <el-form-item label="描述"><el-input v-model="createForm.description" type="textarea" :rows="2" maxlength="500"/></el-form-item>
        </el-form>
        <template #footer><el-button @click="createOpen = false">取消</el-button><el-button type="primary" :loading="createBusy" @click="confirmCreate">创建并编排</el-button></template>
      </el-dialog>
      <el-dialog v-model="startOpen" :title="`启动 ${startTargets.length} 个任务`" width="min(480px, 95vw)">
        <el-form label-position="top"><el-form-item label="业务日期"><el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD" :clearable="false"/></el-form-item></el-form>
        <p v-for="target in startTargets" :key="target.id" class="status-text">{{ target.name }}（版本 {{ target.version }}）</p>
        <template #footer><el-button :disabled="batchBusy" @click="startOpen = false">取消</el-button><el-button type="primary" :loading="batchBusy" @click="confirmStart">启动</el-button></template>
      </el-dialog>
    </template>
    <OrchestratorEditor v-else ref="editorRef" :task-id="editorTaskId" :sources="sources" @back="editorBack" @saved="editorSaved"/>
    <Teleport to="body">
      <div v-if="directoryMenu" class="directory-context-menu" role="menu" aria-label="任务目录操作" :style="{ left: `${directoryMenu.x}px`, top: `${directoryMenu.y}px` }" @pointerdown.stop>
        <template v-if="directoryMenu.node.id === 0">
          <button type="button" role="menuitem" class="directory-context-item" @click="runDirectoryMenuAction('create')">
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M1.7 4.2c0-.8.6-1.4 1.4-1.4h2.6l1.4 1.5h5.8c.8 0 1.4.6 1.4 1.4v5.6c0 .8-.6 1.4-1.4 1.4H3.1c-.8 0-1.4-.6-1.4-1.4Z"/><path d="M8 6.2v4.2M5.9 8.3h4.2"/></svg>新增目录
          </button>
        </template>
        <template v-else>
          <button v-if="can('workflow.task.create')" type="button" role="menuitem" class="directory-context-item" @click="runDirectoryMenuAction('create-child')">
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M1.7 4.2c0-.8.6-1.4 1.4-1.4h2.6l1.4 1.5h5.8c.8 0 1.4.6 1.4 1.4v5.6c0 .8-.6 1.4-1.4 1.4H3.1c-.8 0-1.4-.6-1.4-1.4Z"/><path d="M8 6.2v4.2M5.9 8.3h4.2"/></svg>新增子目录
          </button>
          <span v-if="can('workflow.task.create') && (can('workflow.task.update') || can('workflow.task.delete'))" class="directory-context-divider" role="separator"/>
          <button v-if="can('workflow.task.update')" type="button" role="menuitem" class="directory-context-item" @click="runDirectoryMenuAction('rename')">
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m3 10.9-.7 2.8 2.8-.7 7.3-7.3a1.5 1.5 0 0 0-2.1-2.1Z"/><path d="m9.4 4.5 2.1 2.1"/></svg>重命名
          </button>
          <button v-if="can('workflow.task.delete')" type="button" role="menuitem" class="directory-context-item is-danger" @click="runDirectoryMenuAction('delete')">
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 4.2h11M6 4.2V2.7h4v1.5M4.3 4.7l.6 8.1h6.2l.6-8.1M6.4 6.5v4.5M9.6 6.5v4.5"/></svg>删除
          </button>
        </template>
      </div>
    </Teleport>
  </section>
</template>
<style scoped>
.processing-has-directory{display:flex;flex-direction:column;min-height:100%}
.processing-layout{display:flex;flex:1;gap:14px;align-items:stretch;min-height:0}
.directory-pane{flex:0 0 clamp(180px,15%,210px)}
.directory-tree{flex:1;min-height:0;overflow:auto;padding:10px 8px}
.list-pane{display:flex;flex:1;flex-direction:column;min-width:0;min-height:0}
.list-pane :deep(.task-table){flex:1;min-height:0;background:#fff}
.tree-node{display:flex;align-items:center;gap:6px;flex:1;min-width:0}
.tree-label{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.directory-folder-icon{width:15px;height:14px;flex:none;color:#c3c9d1;fill:currentColor}
.directory-folder-icon .folder-highlight{fill:none;stroke:#fff;stroke-opacity:.72;stroke-width:.85}
.directory-tree :deep(.el-tree-node__content.is-current) .directory-folder-icon{color:#4282c1}
.directory-menu-trigger{display:inline-flex;align-items:center;justify-content:center;width:23px;height:23px;flex:none;margin-left:auto;padding:0;border:0;border-radius:4px;background:transparent;color:#61758e;font-size:19px;line-height:1;opacity:0;cursor:pointer}
.directory-tree :deep(.el-tree-node__content:hover) .directory-menu-trigger,.directory-tree :deep(.el-tree-node__content:focus-within) .directory-menu-trigger{opacity:1}
.directory-menu-trigger:hover{background:#eaf1fa;color:#2e6eae}
.directory-menu-trigger:focus-visible{opacity:1;outline:2px solid rgba(52,120,190,.35);outline-offset:1px}
.directory-context-menu{position:fixed;z-index:3000;width:120px;min-width:120px;padding:5px;background:#fff;border:1px solid #dce4ed;border-radius:6px;box-shadow:0 8px 22px rgba(29,48,76,.17)}
.directory-context-item{display:flex;align-items:center;gap:8px;width:100%;height:34px;padding:0 8px;border:0;border-radius:4px;background:transparent;color:#43566e;text-align:left;white-space:nowrap;font:inherit;font-size:12px;cursor:pointer}
.directory-context-item svg{width:15px;height:15px;flex:none;fill:none;stroke:#7e91a8;stroke-width:1.4;stroke-linecap:round;stroke-linejoin:round}
.directory-context-item:hover,.directory-context-item:focus-visible{background:#f0f5fb;color:#2b69ae;outline:none}
.directory-context-item:hover svg,.directory-context-item:focus-visible svg{stroke:#2b69ae}
.directory-context-item.is-danger{color:#c75454}
.directory-context-item.is-danger svg{stroke:#c75454}
.directory-context-item.is-danger:hover,.directory-context-item.is-danger:focus-visible{background:#fff2f0;color:#b64d4d}
.directory-context-divider{display:block;height:1px;margin:5px 4px;background:#edf0f4}
.status-text{color:#728582;font-size:12px}
.list-pane :deep(.op-column .cell){display:flex;flex-wrap:nowrap;align-items:center;gap:2px;justify-content:center}
.list-pane :deep(.op-column .el-button){margin-left:0;padding:4px 3px;font-size:13px}
.list-pane :deep(.op-column .el-button+.el-button){margin-left:0}
.list-pane :deep(.last-run-column .cell){white-space:nowrap}
.processing-has-directory .list-pane .pagination{display:flex;flex:none;align-items:center;justify-content:flex-end;min-height:50px;margin-top:auto;padding:0 12px;border-top:1px solid var(--el-border-color-lighter);background:#fff}
.directory-select{display:none}
@media(hover:none){.directory-menu-trigger{opacity:1}}
@media(max-width:700px){.processing-has-directory{min-height:0}.processing-layout{flex:initial;min-height:0}.processing-has-directory .list-pane .pagination{margin-top:14px}.directory-pane{display:none}.directory-select{display:block;width:100%;margin-bottom:10px}}
</style>
