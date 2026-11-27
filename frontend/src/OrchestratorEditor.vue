<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/minimap/dist/style.css'
import { ElMessage, ElMessageBox } from 'element-plus'
import OrchPalette from './OrchPalette.vue'
import OrchNode from './OrchNode.vue'
import OrchInspector from './OrchInspector.vue'
import OrchConsole from './OrchConsole.vue'
import * as api from './orch-api.js'
import { startOrchGuide, guideDismissed } from './orch-guide.js'
import { indexComponents, newNode, normalizeNode, canConnect, edgeKind, newEdgeId, autoLayout, toVueNodes, toVueEdges, createHistory, clientValidate, configSummary } from './orch-graph.js'

const props = defineProps({ taskId: [Number, String], sources: Array })
const emit = defineEmits(['back', 'saved'])
const can = inject('canPermission', () => false)
const FLOW_ID = 'orch-editor-' + (props.taskId || 'new')

const components = ref([])
const byType = computed(() => indexComponents(components.value))
const definition = reactive({ schemaVersion: 1, variables: [], nodes: [], edges: [], layout: { zoom: 1, viewport: { x: 0, y: 0 } } })
const name = ref(''), revision = ref(1), publishedVersion = ref(0)
const dirty = ref(false), saving = ref(false), validating = ref(false), publishing = ref(false), loading = ref(true)
const selectedId = ref(null), selectedEdgeId = ref(null)
const issues = ref([])
const showIssues = ref(false)
const history = createHistory(30)
const gridOn = ref(true), minimapOn = ref(false), canvasFullscreen = ref(false)
const consoleOpen = ref(false)
const nodeTypes = { orchNode: OrchNode }

// ---- runtime state ----
const mode = ref('idle')
const activeRunId = ref(null)
const runDetail = ref(null)
const runLogs = ref([])
const testResult = ref(null)
const runBusy = ref(false)
let pollTimer = null, lastLogId = 0

// ---- dialogs ----
const publishOpen = ref(false), publishComment = ref('')
const runOpen = ref(false), runBusinessDate = ref(new Date().toLocaleDateString('sv-SE')), runVariables = reactive({})
const scheduleOpen = ref(false), scheduleForm = reactive({ cron: '0 0 0 * * *', zone: 'Asia/Shanghai', enabled: true })
const directories = ref([])
const taskDirectoryId = ref(null), taskDescription = ref('')
const infoOpen = ref(false), infoBusy = ref(false), infoForm = reactive({ name: '', directoryId: null, description: '' })
const directoryOptions = computed(() => {
  const walk = (pid, depth) => directories.value.filter(d => d.parentId === pid).sort((a, b) => a.sortOrder - b.sortOrder).flatMap(d => [{ id: d.id, label: '　'.repeat(depth) + d.name }, ...walk(d.id, depth + 1)])
  return walk(null, 0)
})
const issuesPanel = computed(() => issues.value)
const issueIds = computed(() => new Set(issues.value.filter(i => i.targetType === 'node').map(i => i.targetId)))

const readonly = computed(() => runDetail.value?.state === 'RUNNING' || mode.value === 'run' && runDetail.value?.state === 'RUNNING')
const canEdit = computed(() => can('workflow.task.update') && !readonly.value)
const nodeStatesMap = computed(() => {
  const map = new Map()
  const states = mode.value === 'test' ? (testResult.value?.nodeStates || []) : (runDetail.value?.nodes || [])
  for (const s of states) map.set(s.nodeKey, s.state)
  return map
})
const consoleNodeStates = computed(() => mode.value === 'test' ? (testResult.value?.nodeStates || []) : (runDetail.value?.nodes || []))
const consoleLogs = computed(() => mode.value === 'test' ? (testResult.value?.logs || []) : runLogs.value)
const consolePreviews = computed(() => mode.value === 'test' ? (testResult.value?.previews || {}) : {})
const selectedNode = computed(() => definition.nodes.find(n => n.id === selectedId.value) || null)
const nodeNameMap = computed(() => Object.fromEntries(definition.nodes.map(n => [n.id, n.name])))
const selectedComponent = computed(() => selectedNode.value ? byType.value.get(selectedNode.value.component) : null)
const selectedNodeLogs = computed(() => consoleLogs.value.filter(l => l.nodeKey === selectedId.value))
const selectedNodeState = computed(() => nodeStatesMap.value.get(selectedId.value) || null)
const sourceFields = ref([]), targetFields = ref([]), tables = ref([]), tablesLoading = ref(false), tablesSourceId = ref(null)

const vueNodes = computed(() => toVueNodes(definition, byType.value, { nodeStates: nodeStatesMap.value, selectedId: selectedId.value, issueIds: issueIds.value, running: readonly.value }))
const vueEdges = computed(() => toVueEdges(definition, { selectedEdgeId: selectedEdgeId.value }))
const zoomPercent = ref(100)
const metricState = computed(() => runDetail.value ? ({ SUCCEEDED: '成功', FAILED: '失败', RUNNING: '运行中', INTERRUPTED: '已中断', PENDING: '排队' }[runDetail.value.state] || runDetail.value.state) : (publishedVersion.value > 0 ? '已发布' : '草稿'))
const metricLastRun = computed(() => runDetail.value?.finishedAt ? String(runDetail.value.finishedAt).replace('T', ' ').slice(5, 16) : '暂无执行')
// 控制台入口状态点：弹框关闭时也能感知最近一次运行状态
const consoleDot = computed(() => {
  const s = mode.value === 'test' ? 'TEST' : (runDetail.value?.state || '')
  if (s === 'RUNNING' || s === 'PENDING') return 'running'
  if (s === 'SUCCEEDED') return 'success'
  if (s === 'FAILED' || s === 'INTERRUPTED' || s === 'CANCELED') return 'danger'
  if (s === 'TEST') return 'test'
  return 'idle'
})

const { onConnect, onNodeClick, onEdgeClick, onPaneClick, onNodesChange, onEdgesChange, onViewportChange, screenToFlowCoordinate, fitView, zoomIn, zoomOut } = useVueFlow(FLOW_ID)

function snapshot() { return JSON.parse(JSON.stringify({ nodes: definition.nodes, edges: definition.edges, variables: definition.variables })) }
function restore(snap) { definition.nodes = snap.nodes; definition.edges = snap.edges; definition.variables = snap.variables }
function markDirty() { if (!readonly.value) dirty.value = true }
function pushHistory() { history.push(snapshot()) }

// ---- load ----
let guideStarted = false
async function load() {
  loading.value = true
  try {
    components.value = await api.fetchComponents()
    const draft = await api.fetchDraft(props.taskId)
    name.value = draft.name
    revision.value = draft.revision
    publishedVersion.value = draft.publishedVersion
    taskDirectoryId.value = draft.directoryId ?? null
    taskDescription.value = draft.description ?? ''
    api.fetchTaskDirectories().then(d => { directories.value = d }).catch(() => {})
    const def = draft.definition
    definition.schemaVersion = 1
    definition.variables = def.variables || []
    definition.nodes = (def.nodes || []).map(normalizeNode)
    definition.edges = def.edges || []
    definition.layout = def.layout || { zoom: 1, viewport: { x: 0, y: 0 } }
    dirty.value = false
    // 默认 100% 展示：仅在有节点时适应画布，且放大不超过 100%
    if (definition.nodes.length) setTimeout(() => fitView({ padding: 0.2, maxZoom: 1, duration: 200 }), 80)
  } catch (e) { ElMessage.error(e.message) } finally {
    loading.value = false
    if (!guideStarted && !guideDismissed()) { guideStarted = true; nextTick(() => setTimeout(() => startOrchGuide(), 500)) }
  }
}

// ---- canvas interactions ----
onConnect(params => {
  if (!canEdit.value) return
  const err = canConnect(definition, byType.value, params)
  if (err) { ElMessage.warning(err); return }
  pushHistory()
  const targetNode = definition.nodes.find(n => n.id === params.target)
  const kind = edgeKind(byType.value.get(targetNode.component), params.targetHandle)
  definition.edges.push({ id: newEdgeId(definition.edges), from: params.source, fromPort: params.sourceHandle || 'out', to: params.target, toPort: params.targetHandle || 'in', kind })
  markDirty()
})
onNodesChange(changes => {
  for (const c of changes) {
    if (c.type === 'position' && c.position && c.dragging === false) {
      const n = definition.nodes.find(n => n.id === c.id)
      if (n) { n.position = { x: c.position.x, y: c.position.y }; markDirty() }
    }
    if (c.type === 'remove') removeNode(c.id, false)
  }
})
onEdgesChange(changes => {
  for (const c of changes) if (c.type === 'remove') { pushHistory(); definition.edges = definition.edges.filter(e => e.id !== c.id); markDirty() }
})
onNodeClick(({ node }) => { selectedId.value = node.id; selectedEdgeId.value = null })
onEdgeClick(({ edge }) => { selectedEdgeId.value = edge.id; selectedId.value = null })
onPaneClick(() => { selectedId.value = null; selectedEdgeId.value = null })
onViewportChange(({ zoom }) => { zoomPercent.value = Math.round(zoom * 100) })

function addNodeAt(type, position) {
  if (!canEdit.value) return
  const comp = byType.value.get(type)
  if (!comp?.enabled) { ElMessage.warning('该组件即将支持'); return }
  pushHistory()
  const node = newNode(type, byType.value, definition.nodes, position)
  definition.nodes.push(node)
  selectedId.value = node.id
  markDirty()
}
function onPaletteAdd(type) {
  const rect = canvasEl.value?.getBoundingClientRect()
  const center = rect ? screenToFlowCoordinate({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }) : { x: 120, y: 120 }
  addNodeAt(type, { x: center.x - 100, y: center.y - 40 })
}
const canvasEl = ref(null)
function onDrop(event) {
  const type = event.dataTransfer.getData('application/orch-component')
  if (!type) return
  event.preventDefault()
  const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  addNodeAt(type, { x: position.x - 100, y: position.y - 30 })
}
function removeNode(id, withHistory = true) {
  if (!canEdit.value) return
  if (withHistory) pushHistory()
  definition.nodes = definition.nodes.filter(n => n.id !== id)
  definition.edges = definition.edges.filter(e => e.from !== id && e.to !== id)
  if (selectedId.value === id) selectedId.value = null
  markDirty()
}
function removeSelected() {
  if (selectedId.value) removeNode(selectedId.value)
  else if (selectedEdgeId.value) { pushHistory(); definition.edges = definition.edges.filter(e => e.id !== selectedEdgeId.value); selectedEdgeId.value = null; markDirty() }
}
function doUndo() { const snap = history.undo(snapshot()); if (snap) { restore(snap); markDirty() } }
function doRedo() { const snap = history.redo(snapshot()); if (snap) { restore(snap); markDirty() } }
function doAutoLayout() { pushHistory(); autoLayout(definition); markDirty(); setTimeout(() => fitView({ padding: 0.2, maxZoom: 1, duration: 300 }), 60) }
function locateNode(id) {
  selectedId.value = id
  fitView({ nodes: [id], maxZoom: 1.2, padding: 0.4, duration: 300 })
}
// 控制台里点节点定位：先关弹框回到画布，避免遮住定位结果
function onConsoleLocate(id) { consoleOpen.value = false; locateNode(id) }

// ---- persistence ----
async function save() {
  if (saving.value || !canEdit.value) return
  saving.value = true
  try {
    const res = await api.saveDraft(props.taskId, revision.value, definition)
    revision.value = res.revision
    dirty.value = false
    ElMessage.success(`草稿已保存（revision ${res.revision}）`)
    emit('saved')
  } catch (e) {
    if (e.status === 409) {
      try {
        await ElMessageBox.confirm(`草稿已被他人修改（当前 revision ${e.payload?.currentRevision}）。刷新将丢弃本地未保存修改。`, '版本冲突', { confirmButtonText: '刷新', cancelButtonText: '继续编辑', type: 'warning' })
        await load()
      } catch { /* keep editing */ }
    } else ElMessage.error(e.message)
  } finally { saving.value = false }
}
async function validate() {
  validating.value = true
  try {
    const res = await api.validateTask(props.taskId)
    issues.value = [...(res.errors || []), ...(res.warnings || [])]
    showIssues.value = issues.value.length > 0
    if (res.valid) ElMessage.success('校验通过' + (res.warnings?.length ? `（${res.warnings.length} 条警告）` : ''))
    else ElMessage.error(`校验未通过：${res.errors.length} 个错误`)
  } catch (e) { ElMessage.error(e.message) } finally { validating.value = false }
}
async function doPublish() {
  publishing.value = true
  try {
    const res = await api.publishTask(props.taskId, revision.value, publishComment.value)
    publishedVersion.value = res.version
    publishOpen.value = false
    publishComment.value = ''
    ElMessage.success(`已发布版本 v${res.version}`)
    emit('saved')
  } catch (e) {
    if (e.status === 422) { issues.value = e.payload?.validation?.errors || []; showIssues.value = true; ElMessage.error('校验未通过，不能发布') }
    else if (e.status === 409) { ElMessage.error('草稿已被他人修改，请刷新'); await load() }
    else ElMessage.error(e.message)
  } finally { publishing.value = false }
}

// ---- runs ----
function openRun() {
  if (publishedVersion.value < 1) { ElMessage.warning('任务尚未发布，请先发布；或使用“运行测试”预览'); return }
  for (const v of definition.variables) if (v.valueSource !== 'WATERMARK') runVariables[v.name] = v.defaultValue ?? ''
  runOpen.value = true
}
async function confirmRun() {
  runBusy.value = true
  try {
    const variables = {}
    for (const v of definition.variables) if (v.valueSource !== 'WATERMARK') variables[v.name] = runVariables[v.name]
    const res = await api.startRun(props.taskId, { version: publishedVersion.value, businessDate: runBusinessDate.value, variables })
    runOpen.value = false
    activeRunId.value = res.id
    mode.value = 'run'
    consoleOpen.value = true
    runLogs.value = []; lastLogId = 0
    await refreshRun()
    startPolling()
    emit('saved')
  } catch (e) { ElMessage.error(e.message) } finally { runBusy.value = false }
}
async function runTest() {
  runBusy.value = true
  try {
    const variables = {}
    for (const v of definition.variables) if (v.valueSource !== 'WATERMARK') variables[v.name] = v.defaultValue ?? ''
    testResult.value = await api.testRun({ definition, businessDate: runBusinessDate.value, variables })
    mode.value = 'test'
    consoleOpen.value = true
    ElMessage.success('运行测试完成（未写入目标）')
  } catch (e) {
    if (e.status === 422) { issues.value = e.payload?.validation?.errors || []; showIssues.value = true; ElMessage.error('校验未通过') }
    else ElMessage.error(e.message)
  } finally { runBusy.value = false }
}
async function refreshRun() {
  if (!activeRunId.value) return
  runBusy.value = true
  try {
    runDetail.value = await api.fetchRunDetail(activeRunId.value)
    const logs = await api.fetchRunLogs(activeRunId.value, { afterId: lastLogId })
    if (logs.length) { runLogs.value.push(...logs); lastLogId = logs[logs.length - 1].id }
  } catch (e) { /* transient */ } finally { runBusy.value = false }
}
function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    await refreshRun()
    if (runDetail.value && !['PENDING', 'RUNNING'].includes(runDetail.value.state)) { stopPolling(); emit('saved') }
  }, 2000)
}
function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } }
async function stopRun() {
  try { await ElMessageBox.confirm('终止会中断运行，可能已写入部分数据。确定终止？', '终止执行', { type: 'warning', confirmButtonText: '终止', cancelButtonText: '取消' }) } catch { return }
  try { await api.stopTask(props.taskId); await refreshRun() } catch (e) { ElMessage.error(e.message) }
}
async function retryRun() {
  try { await ElMessageBox.confirm('重试可能重复写入，确认已检查幂等策略？', '重试', { type: 'warning', confirmButtonText: '重试', cancelButtonText: '取消' }) } catch { return }
  try { await api.retryRun(props.taskId, activeRunId.value); runLogs.value = []; lastLogId = 0; await refreshRun(); startPolling() } catch (e) { ElMessage.error(e.message) }
}
function downloadLogs(text) {
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = `run-${activeRunId.value || 'test'}-logs.txt`; a.click()
  URL.revokeObjectURL(url)
}

// ---- inspector helpers ----
async function testConnection(sourceId) {
  try {
    const { authorizedRequest } = await import('./auth.js')
    const res = await authorizedRequest(`/api/data-sources/${sourceId}/test`, { method: 'POST' })
    if (res.ok) ElMessage.success('连接成功')
    else { const p = await res.json().catch(() => null); ElMessage.error(p?.error || '连接失败') }
  } catch (e) { ElMessage.error(e.message) }
}
async function loadTables(sourceId) {
  if (!sourceId || tablesLoading.value) return
  tablesLoading.value = true
  // 切换数据源后先清掉旧表列表，避免下拉短暂展示别的库的表
  if (tablesSourceId.value !== sourceId) tables.value = []
  try {
    const rows = await api.fetchTables(sourceId)
    tables.value = rows.map(t => t.name || t.tableName || t).filter(Boolean)
    tablesSourceId.value = sourceId
  } catch (e) { tables.value = []; tablesSourceId.value = null; ElMessage.error(e.message) }
  finally { tablesLoading.value = false }
}
function varValues() {
  const values = { business_date: new Date().toLocaleDateString('sv-SE') }
  for (const v of definition.variables || []) values[v.name] = v.defaultValue ?? ''
  return values
}
function varTypes() {
  const types = { business_date: 'DATE' }
  for (const v of definition.variables || []) types[v.name] = v.type
  return types
}
const upstreamOf = id => { const e = definition.edges.find(e => e.to === id && e.kind === 'DATA'); return e ? definition.nodes.find(n => n.id === e.from) : null }
const downstreamOf = id => { const e = definition.edges.find(e => e.from === id); return e ? definition.nodes.find(n => n.id === e.to) : null }
async function resolveSourceSchema(node, depth = 0) {
  if (!node || depth > 6) return []
  const c = node.config || {}
  if (node.component.startsWith('source.') && c.sourceId) {
    const sql = c.readMode === 'SQL' ? c.sql : (c.table ? `SELECT * FROM ${c.table}` : '')
    if (!sql) return []
    return await api.fetchSchema({ sourceId: c.sourceId, sql, values: varValues(), types: varTypes() })
  }
  return resolveSourceSchema(upstreamOf(node.id), depth + 1)
}
async function resolveTargetSchema(node, depth = 0) {
  if (!node || depth > 6) return []
  const c = node.config || {}
  if (node.component.startsWith('sink.') && c.sourceId && c.table) {
    return await api.fetchSchema({ sourceId: c.sourceId, sql: `SELECT * FROM ${c.table}` })
  }
  return resolveTargetSchema(downstreamOf(node.id), depth + 1)
}
async function loadFields(feedback = true) {
  const node = selectedNode.value
  if (!node) return
  sourceFields.value = []; targetFields.value = []
  const errors = []
  try { sourceFields.value = await resolveSourceSchema(upstreamOf(node.id)) } catch (e) { errors.push(`来源字段：${e.message}`) }
  try { targetFields.value = await resolveTargetSchema(downstreamOf(node.id)) } catch (e) { errors.push(`目标字段：${e.message}`) }
  if (!feedback) return
  if (errors.length) ElMessage.error(errors.join('；'))
  else if (!sourceFields.value.length && !targetFields.value.length) ElMessage.warning('未能从上下游加载字段，请检查来源/目标配置')
  else ElMessage.success(`已加载来源字段 ${sourceFields.value.length} 个、目标字段 ${targetFields.value.length} 个`)
}
// 选中映射节点时静默预加载，避免每次点节点都弹提示；手动「加载字段」才给反馈
watch(selectedNode, n => { if (n && n.component === 'transform.field-map') loadFields(false) })

// ---- schedule ----
function openInfo() { infoForm.name = name.value; infoForm.directoryId = taskDirectoryId.value; infoForm.description = taskDescription.value; infoOpen.value = true }
async function saveInfo() {
  if (!infoForm.name.trim()) { ElMessage.warning('请填写任务名称'); return }
  infoBusy.value = true
  try {
    await api.updateTaskMeta(props.taskId, { name: infoForm.name.trim(), directoryId: infoForm.directoryId, description: infoForm.description })
    name.value = infoForm.name.trim(); taskDirectoryId.value = infoForm.directoryId; taskDescription.value = infoForm.description
    infoOpen.value = false; ElMessage.success('任务信息已保存'); emit('saved')
  } catch (e) { ElMessage.error(e.message) } finally { infoBusy.value = false }
}
// ---- version history ----
const versionsOpen = ref(false), versions = ref([]), versionsLoading = ref(false)
const previewOpen = ref(false), previewDef = ref(null), previewMeta = ref(null), previewLoading = ref(false)
const previewNodes = computed(() => previewDef.value ? toVueNodes(previewDef.value, byType.value, {}) : [])
const previewEdges = computed(() => previewDef.value ? toVueEdges(previewDef.value, {}) : [])
const previewNodeSummary = computed(() => (previewDef.value?.nodes || []).map(n => ({ name: n.name, type: byType.value.get(n.component)?.name || n.component, summary: configSummary(byType.value.get(n.component), n.config) })))
const previewSelectedId = ref(null)
const previewSelectedNode = computed(() => (previewDef.value?.nodes || []).find(n => n.id === previewSelectedId.value) || null)
const previewSelectedComponent = computed(() => previewSelectedNode.value ? byType.value.get(previewSelectedNode.value.component) : null)
const { onNodeClick: onPreviewNodeClick } = useVueFlow('orch-version-preview')
onPreviewNodeClick(({ node }) => { previewSelectedId.value = node.id })
async function openVersions() {
  versionsOpen.value = true; versionsLoading.value = true
  try { versions.value = await api.fetchVersions(props.taskId) } catch (e) { ElMessage.error(e.message) } finally { versionsLoading.value = false }
}
async function viewVersion(row) {
  previewOpen.value = true; previewLoading.value = true; previewDef.value = null; previewSelectedId.value = null
  try {
    const v = await api.fetchVersion(props.taskId, row.version)
    previewDef.value = v.definition
    previewMeta.value = { version: v.version, comment: v.comment, publishedBy: v.publishedBy, publishedAt: v.publishedAt }
  } catch (e) { ElMessage.error(e.message) } finally { previewLoading.value = false }
}
async function restoreVersion(row) {
  try { await ElMessageBox.confirm(`将 v${row.version} 的配置载入当前草稿？载入后需保存并发布才会生效。`, '载入历史版本', { confirmButtonText: '载入草稿', cancelButtonText: '取消', type: 'warning' }) } catch { return }
  try {
    const v = await api.fetchVersion(props.taskId, row.version)
    pushHistory()
    definition.nodes = (v.definition.nodes || []).map(normalizeNode)
    definition.edges = v.definition.edges || []
    definition.variables = v.definition.variables || []
    dirty.value = true
    versionsOpen.value = false
    ElMessage.success(`已载入 v${row.version} 为草稿，保存并发布后生效`)
    setTimeout(() => fitView({ padding: 0.2, maxZoom: 1, duration: 200 }), 80)
  } catch (e) { ElMessage.error(e.message) }
}
async function openSchedule() {
  try { const s = await api.fetchSchedule(props.taskId); if (s) { scheduleForm.cron = s.cron; scheduleForm.zone = s.zone; scheduleForm.enabled = s.enabled } } catch { /* none */ }
  scheduleOpen.value = true
}
async function saveSchedule() {
  try { await api.saveSchedule(props.taskId, { ...scheduleForm }); ElMessage.success('定时计划已保存'); scheduleOpen.value = false } catch (e) { ElMessage.error(e.message) }
}
async function removeSchedule() {
  try { await api.deleteSchedule(props.taskId); ElMessage.success('定时计划已删除'); scheduleOpen.value = false } catch (e) { ElMessage.error(e.message) }
}

// ---- navigation / shortcuts ----
async function back() {
  if (dirty.value) {
    try { await ElMessageBox.confirm('有未保存的修改，离开后将丢失。', '确认离开', { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' }) } catch { return }
  }
  emit('back')
}
function isEditingTarget(event) {
  const el = event.target
  return el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable || el.closest?.('.monaco-editor'))
}
function onKeydown(event) {
  const mod = event.ctrlKey || event.metaKey
  if (mod && event.key.toLowerCase() === 's') { event.preventDefault(); save(); return }
  if (isEditingTarget(event)) return
  if (mod && event.key.toLowerCase() === 'z') { event.preventDefault(); event.shiftKey ? doRedo() : doUndo(); return }
  if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); removeSelected(); return }
  if (event.key === 'Escape') { selectedId.value = null; selectedEdgeId.value = null }
}
function onBeforeUnload(event) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }

watch(() => definition.nodes.length, () => { /* metrics reactive */ })
defineExpose({ hasUnsaved: () => dirty.value })

onMounted(() => {
  load()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('beforeunload', onBeforeUnload)
})
onBeforeUnmount(() => {
  stopPolling()
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('beforeunload', onBeforeUnload)
})
</script>

<template>
  <section class="orch-editor" :class="{ fullscreen: canvasFullscreen }" v-loading="loading">
    <!-- header -->
    <div class="editor-header">
      <div class="header-left">
        <el-button text @click="back">← 返回</el-button>
        <strong class="task-name">{{ name }}</strong>
        <button v-if="can('workflow.task.update')" type="button" class="info-edit-btn" title="编辑任务信息（名称/目录/描述）" @click="openInfo">✎</button>
        <el-tag size="small" :type="publishedVersion > 0 ? 'success' : 'info'">{{ publishedVersion > 0 ? `已发布 v${publishedVersion}` : '草稿' }}</el-tag>
        <el-tag v-if="dirty" size="small" type="warning" effect="plain">未保存</el-tag>
        <span class="engine-badge"><svg viewBox="0 0 24 24"><path d="M4 12c4-6 12-6 16 0-4 6-12 6-16 0Z" /></svg>执行引擎：Apache Camel</span>
      </div>
      <div class="header-actions">
        <el-button size="small" class="ghost" :disabled="!canEdit" :loading="saving" @click="save">保存草稿</el-button>
        <el-button size="small" class="ghost" :loading="validating" @click="validate">校验</el-button>
        <el-button size="small" class="ghost" :disabled="!can('workflow.task.publish') || readonly" @click="publishOpen = true">发布</el-button>
        <el-button size="small" class="ghost" :disabled="!can('workflow.task.execute')" :loading="runBusy" @click="runTest">运行测试</el-button>
        <el-button size="small" type="primary" :disabled="!can('workflow.task.execute') || readonly" @click="openRun">运行</el-button>
        <el-button size="small" class="ghost console-btn" title="运行日志 / 节点状态 / 数据预览" @click="consoleOpen = true"><span class="console-dot" :class="consoleDot"></span>运行控制台</el-button>
        <el-button size="small" class="ghost" @click="startOrchGuide()">引导</el-button>
        <el-button size="small" class="ghost" :disabled="!can('workflow.task.read')" @click="openVersions">版本历史</el-button>
        <el-button size="small" class="ghost" :disabled="!can('workflow.task.update')" @click="openSchedule">定时</el-button>
      </div>
    </div>

    <!-- 紧凑状态条 -->
    <div class="metrics">
      <span class="metric"><span class="m-dot blue"></span>节点 <strong>{{ definition.nodes.length }}</strong></span>
      <span class="metric"><span class="m-dot purple"></span>连线 <strong>{{ definition.edges.length }}</strong></span>
      <span class="metric"><span class="m-dot green"></span>状态 <strong>{{ metricState }}</strong></span>
      <span class="metric"><span class="m-dot orange"></span>最近执行 <strong>{{ metricLastRun }}</strong></span>
    </div>

    <!-- validation issues -->
    <div v-if="showIssues && issuesPanel.length" class="issues-panel">
      <div class="issues-head"><strong>校验结果（{{ issuesPanel.length }}）</strong><el-button text size="small" @click="showIssues = false">收起</el-button></div>
      <div v-for="(i, idx) in issuesPanel" :key="idx" class="issue" :class="i.severity === 'ERROR' ? 'error' : 'warn'" @click="i.targetType === 'node' && locateNode(i.targetId)">
        <el-tag size="small" :type="i.severity === 'ERROR' ? 'danger' : 'warning'">{{ i.severity === 'ERROR' ? '错误' : '警告' }}</el-tag>
        <span class="issue-target">{{ i.targetId || '编排' }}</span>
        <span class="issue-msg">{{ i.message }}</span>
      </div>
    </div>

    <!-- body -->
    <div class="editor-body">
      <OrchPalette :components="components" :disabled="!canEdit" @add="onPaletteAdd" />
      <div class="canvas-wrap" :class="{ 'canvas-full': canvasFullscreen }">
        <div class="canvas-toolbar">
          <div class="tb-group">
            <button type="button" title="适应画布" @click="fitView({ padding: 0.2, maxZoom: 1, duration: 200 })">⤢ 适应</button>
            <button type="button" title="放大" @click="zoomIn()">＋</button>
            <span class="zoom">{{ zoomPercent }}%</span>
            <button type="button" title="缩小" @click="zoomOut()">－</button>
          </div>
          <div class="tb-group">
            <button type="button" title="撤销 (Ctrl+Z)" :disabled="!history.canUndo()" @click="doUndo">↶</button>
            <button type="button" title="重做 (Ctrl+Shift+Z)" :disabled="!history.canRedo()" @click="doRedo">↷</button>
          </div>
          <div class="tb-group">
            <button type="button" :class="{ active: gridOn }" title="网格" @click="gridOn = !gridOn">网格</button>
            <button type="button" title="自动布局" @click="doAutoLayout">自动布局</button>
            <button type="button" :class="{ active: minimapOn }" title="小地图" @click="minimapOn = !minimapOn">小地图</button>
            <button type="button" :class="{ active: canvasFullscreen }" title="全屏" @click="canvasFullscreen = !canvasFullscreen">全屏</button>
          </div>
        </div>
        <div ref="canvasEl" class="canvas" @drop="onDrop" @dragover.prevent>
          <!-- 不使用 fit-view-on-init：它会在首个节点落入画布时自动 fitView 改变缩放；默认视口即 100% -->
          <VueFlow :id="FLOW_ID" :nodes="vueNodes" :edges="vueEdges" :node-types="nodeTypes" :default-viewport="{ zoom: 1 }"
                   :min-zoom="0.4" :max-zoom="1.5" :nodes-draggable="canEdit" :nodes-connectable="canEdit" :edges-updatable="canEdit"
                   :delete-key-code="null">
            <Background v-if="gridOn" :gap="20" :size="1" pattern-color="#d6e0ea" />
            <MiniMap v-if="minimapOn" position="bottom-right" :pannable="true" :zoomable="true" />
            <div v-if="!vueNodes.length" class="canvas-empty">从左侧组件库拖拽或点击组件开始编排</div>
          </VueFlow>
        </div>
      </div>
    </div>

    <!-- 节点配置抽屉：点击节点才从右侧滑出，平时隐藏，把空间让给画布 -->
    <Transition name="orch-drawer">
      <aside v-if="selectedNode" class="orch-drawer">
        <OrchInspector :node="selectedNode" :component="selectedComponent" :sources="sources" :variables="definition.variables"
                       :readonly="!canEdit" :node-logs="selectedNodeLogs" :node-state="selectedNodeState" :source-fields="sourceFields" :target-fields="targetFields" :tables="tables" :tables-loading="tablesLoading"
                       @close="selectedId = null" @test-connection="testConnection" @new-datasource="ElMessage.info('请在数据资产模块新建数据源')" @load-tables="loadTables" @load-fields="loadFields" />
      </aside>
    </Transition>

    <!-- 运行控制台：按需弹框，不再常驻底部挤压画布 -->
    <el-dialog v-model="consoleOpen" title="运行控制台" width="min(1080px,92vw)"
               class="orch-console-dialog" append-to-body :close-on-click-modal="false">
      <OrchConsole :mode="mode" :run="runDetail" :logs="consoleLogs" :node-states="consoleNodeStates"
                   :previews="consolePreviews" :run-id="activeRunId" :node-names="nodeNameMap" :busy="runBusy" :can-execute="can('workflow.task.execute')" :can-retry="can('workflow.task.retry')"
                   @stop="stopRun" @refresh="refreshRun" @retry="retryRun" @locate="onConsoleLocate" @download="downloadLogs" />
    </el-dialog>

    <!-- publish dialog -->
    <el-dialog v-model="publishOpen" title="发布版本" width="min(480px,95vw)">
      <el-alert type="info" :closable="false" show-icon title="发布将冻结当前草稿为不可变版本；运行固定使用已发布版本。" />
      <el-form label-position="top" style="margin-top:12px"><el-form-item label="发布说明"><el-input v-model="publishComment" type="textarea" :rows="3" maxlength="500" placeholder="选填" /></el-form-item></el-form>
      <template #footer><el-button @click="publishOpen = false">取消</el-button><el-button type="primary" :loading="publishing" @click="doPublish">发布</el-button></template>
    </el-dialog>

    <!-- run dialog -->
    <el-dialog v-model="runOpen" title="运行已发布版本" width="min(520px,95vw)">
      <el-alert type="warning" :closable="false" show-icon title="真实运行会写入目标数据源，请确认写入幂等性与业务日期。" />
      <el-form label-position="top" style="margin-top:12px">
        <el-form-item label="业务日期"><el-date-picker v-model="runBusinessDate" type="date" value-format="YYYY-MM-DD" :clearable="false" /></el-form-item>
        <el-form-item v-for="v in definition.variables.filter(x => x.valueSource !== 'WATERMARK')" :key="v.name" :label="`${v.name}（${v.type}）`">
          <el-input v-model="runVariables[v.name]" :placeholder="v.description || '运行变量值'" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="runOpen = false">取消</el-button><el-button type="primary" :loading="runBusy" @click="confirmRun">运行</el-button></template>
    </el-dialog>

    <!-- version history dialog -->
    <el-dialog v-model="versionsOpen" title="版本历史" width="min(780px,96vw)">
      <el-alert type="info" :closable="false" show-icon title="每次发布生成一个不可变版本；运行固定使用已发布版本。可将历史版本载入草稿重新编辑发布。" style="margin-bottom:12px" />
      <el-table :data="versions" v-loading="versionsLoading" size="small" empty-text="暂无已发布版本" max-height="360">
        <el-table-column label="版本" width="70"><template #default="{ row }">v{{ row.version }}</template></el-table-column>
        <el-table-column label="发布说明" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.comment || '—' }}</template></el-table-column>
        <el-table-column prop="publishedBy" label="发布人" width="110" />
        <el-table-column label="发布时间" min-width="160"><template #default="{ row }">{{ row.publishedAt ? String(row.publishedAt).replace('T', ' ').slice(0, 19) : '—' }}</template></el-table-column>
        <el-table-column label="操作" width="150"><template #default="{ row }">
          <el-button text type="primary" size="small" @click="viewVersion(row)">查看</el-button>
          <el-button v-if="can('workflow.task.update')" text type="warning" size="small" @click="restoreVersion(row)">载入草稿</el-button>
        </template></el-table-column>
      </el-table>
    </el-dialog>

    <!-- version preview dialog (read-only) -->
    <el-dialog v-model="previewOpen" :title="`版本 v${previewMeta?.version || ''} 配置（只读）`" width="min(1000px,96vw)" top="6vh">
      <div v-loading="previewLoading">
        <div v-if="previewMeta" class="version-meta">
          <span>发布人 {{ previewMeta.publishedBy || '—' }}</span>
          <span>时间 {{ previewMeta.publishedAt ? String(previewMeta.publishedAt).replace('T', ' ').slice(0, 19) : '—' }}</span>
          <span>说明 {{ previewMeta.comment || '—' }}</span>
          <span class="version-hint">点击画布节点查看配置</span>
        </div>
        <div class="version-preview-body">
          <div class="version-canvas">
            <VueFlow id="orch-version-preview" :nodes="previewNodes" :edges="previewEdges" :node-types="nodeTypes"
                     :nodes-draggable="false" :nodes-connectable="false" :edges-updatable="false" :delete-key-code="null"
                     :zoom-on-scroll="false" fit-view-on-init :default-viewport="{ zoom: 0.8 }">
              <Background :gap="20" :size="1" pattern-color="#d6e0ea" />
            </VueFlow>
          </div>
          <div v-if="previewSelectedNode" class="version-inspector">
            <OrchInspector :node="previewSelectedNode" :component="previewSelectedComponent" :sources="sources"
                           :variables="previewDef?.variables || []" :readonly="true" :node-logs="[]" :node-state="null"
                           :source-fields="[]" :target-fields="[]" :tables="[]" @close="previewSelectedId = null" />
          </div>
        </div>
        <el-table :data="previewNodeSummary" size="small" max-height="200" empty-text="无节点" style="margin-top:12px">
          <el-table-column prop="name" label="节点" min-width="140" />
          <el-table-column prop="type" label="组件" width="140" />
          <el-table-column prop="summary" label="配置摘要" min-width="240" show-overflow-tooltip />
        </el-table>
      </div>
    </el-dialog>

    <!-- task info dialog -->
    <el-dialog v-model="infoOpen" title="任务信息" width="min(480px,95vw)">
      <el-form label-position="top">
        <el-form-item label="任务名称" required><el-input v-model="infoForm.name" maxlength="160" placeholder="任务名称" /></el-form-item>
        <el-form-item label="所属目录"><el-select v-model="infoForm.directoryId" clearable placeholder="未分类"><el-option v-for="o in directoryOptions" :key="o.id" :label="o.label" :value="o.id" /></el-select></el-form-item>
        <el-form-item label="描述"><el-input v-model="infoForm.description" type="textarea" :rows="3" maxlength="500" placeholder="选填" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="infoOpen = false">取消</el-button><el-button type="primary" :loading="infoBusy" @click="saveInfo">保存</el-button></template>
    </el-dialog>

    <!-- schedule dialog -->
    <el-dialog v-model="scheduleOpen" title="定时计划" width="min(480px,95vw)">
      <el-form label-position="top">
        <el-form-item label="Cron 表达式（Spring 6 字段）"><el-input v-model="scheduleForm.cron" placeholder="0 0 0 * * *" /></el-form-item>
        <el-form-item label="时区"><el-input v-model="scheduleForm.zone" placeholder="Asia/Shanghai" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="scheduleForm.enabled" /></el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon title="定时运行固定使用已发布版本；错过的触发不补跑。" />
      <template #footer><el-button type="danger" text @click="removeSchedule">删除计划</el-button><el-button @click="scheduleOpen = false">取消</el-button><el-button type="primary" @click="saveSchedule">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.orch-editor{display:flex;flex-direction:column;gap:8px;height:calc(100vh - 200px);min-height:560px}
.editor-header{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}
.header-left{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.task-name{font-size:16px;color:#24344d}
.info-edit-btn{border:0;background:transparent;color:#8a94a6;font-size:15px;cursor:pointer;padding:2px 6px;border-radius:5px;line-height:1}
.info-edit-btn:hover{background:#ecf5ff;color:var(--el-color-primary)}
.version-meta{display:flex;gap:18px;flex-wrap:wrap;font-size:12px;color:#5a6b82;margin-bottom:10px}
.version-hint{color:#9aa4b2}
.version-preview-body{display:flex;gap:12px;align-items:stretch}
.version-canvas{flex:1;min-width:0;height:420px;border:1px solid var(--el-border-color-lighter);border-radius:8px;background:#f8fafc;overflow:hidden}
.version-inspector{flex:0 0 340px;height:420px;border:1px solid var(--el-border-color-lighter);border-radius:8px;overflow:hidden}
.engine-badge{display:inline-flex;align-items:center;gap:5px;font-size:12px;color:#d98a2b;background:#fdf3e0;border-radius:14px;padding:3px 10px}
.engine-badge svg{width:14px;height:14px;fill:none;stroke:#d98a2b;stroke-width:1.6}
.header-actions{display:flex;gap:6px;flex-wrap:wrap;align-items:center}
.header-actions :deep(.el-button){min-height:28px!important;height:28px;padding:0 12px;font-size:13px!important;border-radius:6px;margin-left:0;font-weight:500}
.header-actions :deep(.el-button+.el-button){margin-left:0}
.header-actions :deep(.ghost){color:#5a6b82;border-color:#dce4ed;background:#fff}
.header-actions :deep(.ghost:hover:not(:disabled)){color:var(--el-color-primary);border-color:#b3d4fc;background:#f5f9ff}
.header-actions :deep(.ghost.is-disabled){color:#b6bfc9;border-color:#e8edf3;background:#fafbfc}
.header-actions :deep(.el-button--primary){padding:0 16px;font-weight:600}
.header-left :deep(.el-button){min-height:28px!important;height:28px;font-size:13px!important;padding:0 8px}
.metrics{display:flex;align-items:center;gap:22px;flex-wrap:wrap;padding:0 2px}
.metric{display:inline-flex;align-items:center;gap:6px;font-size:12px;color:#8a94a6}
.metric strong{font-size:13px;color:#24344d;font-weight:600}
.m-dot{width:7px;height:7px;border-radius:50%;flex:none}
.m-dot.blue{background:#2f7fd1}.m-dot.purple{background:#8250df}.m-dot.green{background:#2f9e57}.m-dot.orange{background:#d98a2b}
.issues-panel{background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:8px;padding:8px 12px;max-height:160px;overflow:auto}
.issues-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;font-size:13px;color:#24344d}
.issue{display:flex;align-items:center;gap:8px;padding:4px 6px;border-radius:4px;cursor:pointer;font-size:12px}
.issue:hover{background:#f5f7fa}
.issue-target{color:#2f7fd1;font-family:ui-monospace,Menlo,monospace}
.issue-msg{color:#5a6b82}
.editor-body{flex:1;display:flex;gap:12px;min-height:0}
.canvas-wrap{flex:1;display:flex;flex-direction:column;min-width:0;min-height:0;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:8px;overflow:hidden}
/* 低于 Element Plus 弹层基准(2000+)，保证全屏画布上仍能打开控制台弹框/确认框 */
.canvas-wrap.canvas-full{position:fixed;inset:16px;z-index:1900;box-shadow:0 12px 48px rgba(0,0,0,.25)}
.canvas-toolbar{display:flex;align-items:center;gap:14px;padding:6px 10px;border-bottom:1px solid var(--el-border-color-lighter);flex-wrap:wrap}
.tb-group{display:flex;align-items:center;gap:4px}
.tb-group button{border:1px solid var(--el-border-color-lighter);background:#fff;border-radius:5px;padding:3px 9px;font-size:12px;color:#5a6b82;cursor:pointer}
.tb-group button:hover:not(:disabled){color:var(--el-color-primary);border-color:var(--el-color-primary)}
.tb-group button.active{color:var(--el-color-primary);border-color:var(--el-color-primary);background:#ecf5ff}
.tb-group button:disabled{opacity:.45;cursor:not-allowed}
.zoom{font-size:12px;color:#8a94a6;min-width:42px;text-align:center}
.canvas{flex:1;min-height:0;position:relative;background:#f8fafc}
.canvas-empty{position:absolute;top:45%;left:0;right:0;text-align:center;color:#9aa4b2;pointer-events:none;font-size:13px}
.console-dot{width:7px;height:7px;border-radius:50%;flex:none;background:#c3c9d1;margin-right:6px}
.console-dot.running{background:#2f7fd1;animation:console-pulse 1.2s ease-in-out infinite}
.console-dot.success{background:#2f9e57}
.console-dot.danger{background:#e05656}
.console-dot.test{background:#8250df}
@keyframes console-pulse{0%,100%{opacity:1}50%{opacity:.35}}
@media (prefers-reduced-motion: reduce){.console-dot.running{animation:none}}
.orch-drawer{position:fixed;top:0;right:0;height:100vh;height:100dvh;width:460px;max-width:92vw;background:#fff;z-index:1500;box-shadow:-8px 0 28px rgba(20,40,70,.18);display:flex;flex-direction:column}
.orch-drawer-enter-from,.orch-drawer-leave-to{transform:translateX(100%)}
.orch-drawer-enter-active,.orch-drawer-leave-active{transition:transform .25s ease}
@media(max-width:900px){.editor-body{flex-direction:column;overflow:auto}.metrics{gap:14px}.canvas-wrap{min-height:420px}}
</style>

<style>
/* 步骤引导（driver.js 挂载到 body，需全局样式） */
.orch-guide-popover{font-family:"PingFang SC","Microsoft YaHei",sans-serif;border-radius:10px;box-shadow:0 12px 40px rgba(20,40,70,.22);max-width:340px}
.orch-guide-popover .driver-popover-title{font-size:15px;font-weight:600;color:#24344d}
.orch-guide-popover .driver-popover-description{font-size:13px;color:#5a6b82;line-height:1.7;margin-top:6px}
.orch-guide-popover .driver-popover-footer{display:flex;align-items:center;gap:10px;margin-top:14px}
.orch-guide-popover .driver-popover-progress-text{font-size:12px;color:#9aa4b2}
.orch-guide-popover .driver-popover-navigation-btns{gap:6px}
.orch-guide-popover .driver-popover-navigation-btns button{border-radius:6px;font-size:13px;padding:5px 12px;text-shadow:none}
.orch-guide-popover .driver-popover-prev-btn{background:#fff;border:1px solid #dce4ed;color:#5a6b82}
.orch-guide-popover .driver-popover-next-btn{background:#2f7fd1;border:1px solid #2f7fd1;color:#fff}
.orch-guide-popover .driver-popover-close-btn{color:#9aa4b2}
.orch-guide-optout{display:flex;align-items:center;gap:5px;font-size:12px;color:#8a94a6;cursor:pointer;margin-right:auto;user-select:none;white-space:nowrap}
.orch-guide-optout input{accent-color:#2f7fd1;cursor:pointer;margin:0}
</style>