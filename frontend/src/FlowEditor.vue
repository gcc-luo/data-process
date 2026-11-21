<script setup>
import { computed, inject, ref, watch } from 'vue'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import { ElMessageBox } from 'element-plus'
import { authorizedRequest } from './auth.js'
import { COMPONENTS, GROUPS, componentDef, defaultConfig } from './flow-components.js'
import { validateFlow, toVueNodes, toVueEdges, newNode, canConnect, previewTable } from './flow-graph.js'
import { configToForm, formToConfig } from './flow-config.js'
import FlowNode from './FlowNode.vue'
const props = defineProps({ draft: Object, sources: Array, readonly: Boolean })
const emit = defineEmits(['saved', 'cancel'])
const can = inject('canPermission', () => false)
const FLOW_ID = 'processing-flow-editor'
const graph = ref({ nodes: [], edges: [] })
const error = ref(''), busy = ref(false)
const drawerOpen = ref(false), editingKey = ref(null), configForm = ref({}), selectedKey = ref(null)
const testOpen = ref(false), testBusy = ref(false), testResult = ref(null), testTab = ref('logs'), previewTab = ref('')
const nodeTypes = { flowNode: FlowNode }
const vueNodes = computed(() => toVueNodes(graph.value))
const vueEdges = computed(() => toVueEdges(graph.value))
const { onConnect, onNodeClick, onNodesChange, onEdgesChange, screenToFlowCoordinate } = useVueFlow(FLOW_ID)
const canvasEl = ref(null)
watch(() => props.draft, draft => {
  error.value = ''; selectedKey.value = null
  if (draft?.configuration) {
    try {
      const parsed = JSON.parse(draft.configuration)
      // 形状守卫：非 { nodes: [], edges: [] } 结构一律降级空画布，避免后续渲染崩溃
      if (!parsed || !Array.isArray(parsed.nodes) || !Array.isArray(parsed.edges)) throw new Error('bad')
      graph.value = parsed
    } catch { graph.value = { nodes: [], edges: [] }; error.value = '任务配置解析失败' }
  }
  else graph.value = { nodes: [], edges: [] }
}, { immediate: true })
onNodesChange(changes => {
  for (const c of changes) {
    if (c.type === 'position' && c.position && c.dragging === false) { const n = graph.value.nodes.find(n => n.key === c.id); if (n) n.position = { x: c.position.x, y: c.position.y } }
    if (c.type === 'remove') { graph.value.nodes = graph.value.nodes.filter(n => n.key !== c.id); if (selectedKey.value === c.id) selectedKey.value = null }
  }
})
onEdgesChange(changes => {
  for (const c of changes) if (c.type === 'remove') graph.value.edges = graph.value.edges.filter(e => `${e.from}->${e.to}` !== c.id)
})
onConnect(params => {
  const err = canConnect(graph.value, params)
  if (err) { error.value = err; return }
  graph.value.edges = [...graph.value.edges, { from: params.source, to: params.target }]
  error.value = ''
})
onNodeClick(({ node }) => { selectedKey.value = node.id; openConfig(node.id) })
function removeSelectedNode() {
  const key = selectedKey.value
  if (!key || props.readonly) return
  graph.value.nodes = graph.value.nodes.filter(n => n.key !== key)
  graph.value.edges = graph.value.edges.filter(e => e.from !== key && e.to !== key)
  selectedKey.value = null
}
function addComponent(component) {
  const rect = canvasEl.value?.getBoundingClientRect()
  const offset = graph.value.nodes.length * 30
  const screen = rect ? { x: rect.left + 120 + offset, y: rect.top + 80 + offset } : null
  const position = screen && screenToFlowCoordinate ? screenToFlowCoordinate(screen) : { x: 120 + offset, y: 80 + offset }
  graph.value.nodes = [...graph.value.nodes, newNode(component, graph.value.nodes, position)]
}
function openConfig(key) {
  const node = graph.value.nodes.find(n => n.key === key)
  if (!node) return
  // 旧版本可能含当前平台不认识的组件：提示而不崩溃，不开抽屉
  try { componentDef(node.component) } catch { error.value = `未知组件: ${node.component}，请删除该节点或升级平台`; return }
  editingKey.value = key
  configForm.value = configToForm(node.component, node.config || defaultConfig(node.component))
  drawerOpen.value = true
}
function saveConfig() {
  if (props.readonly) return
  const node = graph.value.nodes.find(n => n.key === editingKey.value)
  if (!node) { drawerOpen.value = false; return }
  const config = formToConfig(node.component, configForm.value)
  node.config = config
  drawerOpen.value = false
}
const editingDef = computed(() => { const n = graph.value.nodes.find(n => n.key === editingKey.value); if (!n) return null; try { return componentDef(n.component) } catch { return null } })
function addMapping() {
  configForm.value.mappings ??= []
  configForm.value.mappings.push({ from: '', to: '' })
}
async function save() {
  if (busy.value || !props.draft) return
  const errors = validateFlow(graph.value)
  if (errors.length) { error.value = errors.join('；'); return }
  busy.value = true; error.value = ''
  try {
    const url = props.draft?.id ? `/api/tasks/${props.draft.id}/versions` : '/api/tasks'
    const body = props.draft?.id ? { configuration: graph.value } : { name: props.draft?.name, type: 'FLOW', directoryId: props.draft?.directoryId ?? null, description: props.draft?.description ?? null, configuration: graph.value }
    const response = await authorizedRequest(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有任务保存权限' : response.status === 409 ? '任务运行中，不能修改' : response.status === 400 ? '任务名称或配置无效，请返回检查名称与节点配置' : '流程保存失败，请检查配置')
    emit('saved')
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
async function testRun() {
  const errors = validateFlow(graph.value)
  if (errors.length) { error.value = errors.join('；'); return }
  testBusy.value = true; error.value = ''; testResult.value = null
  try {
    const response = await authorizedRequest('/api/tasks/flow/test-run', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ configuration: graph.value }) })
    if (!response.ok) throw new Error(response.status === 408 ? '试运行超过 30 秒' : response.status === 403 ? '当前账号没有运行权限' : '试运行失败，请检查配置与数据源')
    testResult.value = await response.json()
    testTab.value = 'logs'
    previewTab.value = Object.keys(testResult.value.previews || {})[0] || ''
    testOpen.value = true
  } catch (e) { error.value = e.message } finally { testBusy.value = false }
}
async function leave() {
  try { await ElMessageBox.confirm('离开后未保存的编排将丢失。', '确认离开', { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' }) } catch { return }
  emit('cancel')
}
const previewKeys = computed(() => Object.keys(testResult.value?.previews || {}))
const previewTables = computed(() => Object.fromEntries(previewKeys.value.map(k => [k, previewTable(testResult.value.previews[k])])))
</script>
<template>
  <section class="flow-editor">
    <el-alert v-if="readonly" title="任务运行中，仅查看；保存与试运行不可用" type="warning" :closable="false" show-icon/>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <div class="toolbar">
      <div><el-button text @click="leave">← 返回任务列表</el-button><strong>{{ draft?.id ? '编辑流程' : '新建流程' }}：{{ draft?.name }}</strong></div>
      <div class="row">
        <el-button :disabled="readonly || !selectedKey" @click="removeSelectedNode">删除节点</el-button>
        <el-button v-if="!readonly && can('workflow.task.execute')" :loading="testBusy" @click="testRun">试运行</el-button>
        <el-button v-if="!readonly && (draft?.id ? can('workflow.task.update') : can('workflow.task.create'))" type="primary" :loading="busy" @click="save">保存</el-button>
      </div>
    </div>
    <div class="editor-layout">
      <aside class="palette">
        <div v-for="g in GROUPS" :key="g.key" class="palette-group">
          <strong>{{ g.label }}</strong>
          <el-button v-for="c in COMPONENTS.filter(c => c.group === g.key)" :key="c.component" class="palette-item" :disabled="readonly" @click="addComponent(c.component)">{{ c.label }}</el-button>
        </div>
        <p>点击组件加入画布；拖动节点调整位置；从节点右侧连接点拖至下一节点左侧建立连线；点击节点配置。</p>
      </aside>
      <div class="canvas" ref="canvasEl" aria-label="流程编排画布">
        <VueFlow :id="FLOW_ID" :nodes="vueNodes" :edges="vueEdges" :node-types="nodeTypes" :default-viewport="{ zoom: 0.9 }" :min-zoom="0.4" :max-zoom="1.5" fit-view-on-init :nodes-draggable="!readonly" :nodes-connectable="!readonly" :delete-key-code="readonly ? null : ['Backspace','Delete']">
          <Background :gap="20" :size="1" pattern-color="#d6e4e1" />
          <Controls position="bottom-right" :show-interactive="false" />
          <div v-if="!vueNodes.length" class="empty">从左侧点击组件开始编排。</div>
        </VueFlow>
      </div>
    </div>
    <el-drawer v-model="drawerOpen" :title="editingDef ? `组件配置：${editingDef.label}` : '组件配置'" size="30%" direction="rtl">
      <el-form v-if="editingDef" label-position="top" :disabled="readonly">
        <el-form-item v-for="f in editingDef.fields" :key="f.key" :label="f.label">
          <el-select v-if="f.type === 'source'" v-model="configForm[f.key]" clearable placeholder="选择数据源"><el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id"/></el-select>
          <el-select v-else-if="f.type === 'select'" v-model="configForm[f.key]"><el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value"/></el-select>
          <el-input-number v-else-if="f.type === 'number'" v-model="configForm[f.key]" :min="f.min ?? 1" :max="f.max ?? 10000"/>
          <el-input v-else-if="f.type === 'textarea' || f.type === 'expr'" v-model="configForm[f.key]" type="textarea" :rows="3"/>
          <el-input v-else-if="f.type === 'list'" v-model="configForm[f.key]" placeholder="逗号分隔"/>
          <div v-else-if="f.type === 'mappings'" class="mappings">
            <div v-for="(m, i) in configForm.mappings" :key="i" class="row">
              <el-input v-model="m.from" placeholder="上游字段"/>
              <span>→</span>
              <el-input v-model="m.to" placeholder="输出字段"/>
              <el-button text type="danger" @click="configForm.mappings.splice(i, 1)">删除</el-button>
            </div>
            <el-button text type="primary" @click="addMapping">添加映射</el-button>
          </div>
          <el-input v-else v-model="configForm[f.key]"/>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="drawerOpen = false">取消</el-button><el-button v-if="!readonly" type="primary" @click="saveConfig">确定</el-button></template>
    </el-drawer>
    <el-dialog v-model="testOpen" title="试运行结果" width="min(900px, 95vw)">
      <el-tabs v-model="testTab">
        <el-tab-pane label="运行日志" name="logs">
          <div class="logs"><div v-for="line in testResult?.logs || []" :key="line.lineNo" :class="['log-line', (line.level || 'INFO').toLowerCase()]">[{{ line.level }}] {{ line.message }}</div><div v-if="!(testResult?.logs || []).length" class="status-text">无日志</div></div>
        </el-tab-pane>
        <el-tab-pane label="数据预览" name="preview">
          <el-alert title="预览仅基于每个来源的前 100 行，不代表全表比例。" type="info" :closable="false" show-icon/>
          <el-tabs v-model="previewTab" type="card">
            <el-tab-pane v-for="key in previewKeys" :key="key" :label="`目标 ${key}`" :name="key">
              <el-table :data="previewTables[key].rows" max-height="360">
                <el-table-column v-for="c in previewTables[key].columns" :key="c.prop" :prop="c.prop" :label="c.label" min-width="120"/>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </section>
</template>
<style scoped>
.toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:12px 0}
.row{display:flex;gap:10px;align-items:center}
.editor-layout{display:flex;gap:14px;align-items:stretch}
.palette{flex:0 0 200px;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:4px;padding:12px;display:flex;flex-direction:column;gap:10px}
.palette-group{display:flex;flex-direction:column;gap:6px}
.palette-item{justify-content:flex-start;margin-left:0}
.palette p{color:#728582;font-size:12px}
.canvas{flex:1;min-width:0;height:560px;border:1px solid #cbdcda;border-radius:8px;background:#f8fbfa;position:relative}
.empty{position:absolute;top:45%;left:0;right:0;text-align:center;color:#718581;pointer-events:none}
.mappings .row{margin:6px 0}
.logs{max-height:360px;overflow:auto;font-family:monospace;font-size:12px}
.log-line.error{color:#c45656}
.log-line.warn{color:#b88230}
.status-text{color:#728582;font-size:12px}
@media(max-width:700px){.editor-layout{flex-direction:column}.palette{flex:none;width:100%}.canvas{height:420px}}
</style>
