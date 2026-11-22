<script setup>
import { computed, ref, watch } from 'vue'
import { previewTable } from './orch-graph.js'
import * as api from './orch-api.js'

const props = defineProps({
  mode: { type: String, default: 'idle' }, run: Object, logs: Array, nodeStates: Array,
  previews: Object, runId: [Number, String], nodeNames: Object, busy: Boolean, canExecute: Boolean, canRetry: Boolean
})
const emit = defineEmits(['stop', 'refresh', 'retry', 'locate', 'update:collapsed', 'download'])
const tab = ref('logs')
const expanded = ref(false)
const logNode = ref('')
const logLevel = ref('')
const logSearch = ref('')
const previewNode = ref('')

const STATE_LABELS = { PENDING: '排队', RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', INTERRUPTED: '已中断', SKIPPED: '跳过', CANCELED: '已取消' }
const STATE_TYPE = { SUCCEEDED: 'success', FAILED: 'danger', INTERRUPTED: 'warning', RUNNING: 'primary', SKIPPED: 'info', PENDING: 'info' }
const stateText = s => STATE_LABELS[s] || s || '—'

const summaryState = computed(() => props.run?.state || (props.mode === 'test' ? 'TEST' : null))
const duration = computed(() => {
  const r = props.run
  if (!r?.startedAt) return '—'
  const end = r.finishedAt ? new Date(r.finishedAt) : new Date()
  const ms = end - new Date(r.startedAt)
  return ms >= 0 ? (ms / 1000).toFixed(1) + ' s' : '—'
})
const filteredLogs = computed(() => {
  const kw = logSearch.value.trim().toLowerCase()
  return (props.logs || []).filter(l =>
    (!logNode.value || l.nodeKey === logNode.value) &&
    (!logLevel.value || l.level === logLevel.value) &&
    (!kw || String(l.message || '').toLowerCase().includes(kw)))
})
const nodeOptions = computed(() => (props.nodeStates || []).map(n => ({ key: n.nodeKey, name: n.nodeName || n.nodeKey })))
const previewKeys = computed(() => props.mode === 'test'
  ? Object.keys(props.previews || {})
  : (props.nodeStates || []).filter(n => n.hasSample).map(n => n.nodeKey))
const previewOptions = computed(() => props.mode === 'test'
  ? Object.keys(props.previews || {}).map(k => ({ key: k, name: props.nodeNames?.[k] || k }))
  : (props.nodeStates || []).filter(n => n.hasSample).map(n => ({ key: n.nodeKey, name: n.nodeName || n.nodeKey })))
const runPreview = ref(null)
const previewLoading = ref(false)
const previewData = computed(() => {
  if (!previewNode.value) return { columns: [], rows: [] }
  if (props.mode === 'test') return previewTable(props.previews[previewNode.value])
  return previewTable(runPreview.value)
})
watch([previewNode, () => props.mode, () => props.runId], async ([node, mode, runId]) => {
  runPreview.value = null
  if (mode !== 'run' || !node || !runId) return
  previewLoading.value = true
  try { runPreview.value = await api.fetchRunNodePreview(runId, node) } catch { runPreview.value = null } finally { previewLoading.value = false }
})
function fmtTime(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '—' }
function downloadLogs() {
  const text = filteredLogs.value.map(l => `${fmtTime(l.createdAt)} [${l.level}] ${l.nodeKey || ''} ${l.message}`).join('\n')
  emit('download', text)
}
</script>

<template>
  <section class="console" :class="{ expanded }">
    <div class="console-bar">
      <div class="console-summary">
        <svg viewBox="0 0 24 24" class="console-icon"><path d="M4 17l6-5-6-5M12 19h8" /></svg>
        <template v-if="mode === 'run' && run">
          <span class="sum-item">执行 <strong>#{{ run.id }}</strong></span>
          <el-tag size="small" :type="STATE_TYPE[run.state] || 'info'">{{ stateText(run.state) }}</el-tag>
          <span class="sum-item">耗时 {{ duration }}</span>
          <span class="sum-item">开始 {{ fmtTime(run.startedAt) }}</span>
        </template>
        <template v-else-if="mode === 'test'">
          <span class="sum-item"><strong>运行测试</strong>（不写目标）</span>
          <el-tag size="small" type="info">试运行</el-tag>
        </template>
        <span v-else class="sum-item muted">尚未运行 · 点击“运行测试”预览，或发布后“运行”。</span>
      </div>
      <div class="console-actions">
        <el-button v-if="mode === 'run' && run?.state === 'RUNNING' && canExecute" size="small" type="warning" :loading="busy" @click="emit('stop')">终止</el-button>
        <el-button v-if="mode === 'run' && ['FAILED', 'INTERRUPTED'].includes(run?.state) && canRetry" size="small" type="warning" @click="emit('retry')">重试</el-button>
        <el-button size="small" :loading="busy" @click="emit('refresh')">刷新</el-button>
        <el-button size="small" text @click="expanded = !expanded">{{ expanded ? '收起全屏' : '全屏' }}</el-button>
        <el-button size="small" text @click="emit('update:collapsed', true)">折叠</el-button>
      </div>
    </div>
    <el-tabs v-model="tab" class="console-tabs">
      <el-tab-pane label="运行日志" name="logs">
        <div class="log-filters">
          <el-select v-model="logNode" size="small" clearable placeholder="全部节点" style="width:150px"><el-option v-for="n in nodeOptions" :key="n.key" :label="n.name" :value="n.key" /></el-select>
          <el-select v-model="logLevel" size="small" clearable placeholder="级别" style="width:100px"><el-option label="INFO" value="INFO" /><el-option label="WARN" value="WARN" /><el-option label="ERROR" value="ERROR" /></el-select>
          <el-input v-model="logSearch" size="small" clearable placeholder="搜索日志" style="width:180px" />
          <el-button size="small" text :disabled="!filteredLogs.length" @click="downloadLogs">下载</el-button>
        </div>
        <div class="log-body">
          <div v-if="!filteredLogs.length" class="console-empty">无日志</div>
          <div v-for="l in filteredLogs" :key="l.id || l.lineNo" class="log-line" :class="(l.level || 'INFO').toLowerCase()">
            <span class="log-time">{{ fmtTime(l.createdAt) }}</span>
            <span class="log-level">[{{ l.level }}]</span>
            <span v-if="l.nodeKey" class="log-node">{{ l.nodeKey }}</span>
            <span class="log-msg">{{ l.message }}</span>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="节点状态" name="nodes">
        <el-table :data="nodeStates || []" size="small" max-height="220" empty-text="尚无节点状态" @row-click="row => emit('locate', row.nodeKey)">
          <el-table-column label="节点" min-width="120"><template #default="{ row }">{{ row.nodeName || row.nodeKey }}</template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" :type="STATE_TYPE[row.state] || 'info'">{{ stateText(row.state) }}</el-tag></template></el-table-column>
          <el-table-column prop="attempt" label="尝试" width="60" />
          <el-table-column prop="rowsIn" label="入行数" width="80" />
          <el-table-column prop="rowsOut" label="出行数" width="80" />
          <el-table-column label="耗时" width="90"><template #default="{ row }">{{ row.durationMs != null ? row.durationMs + ' ms' : '—' }}</template></el-table-column>
          <el-table-column prop="errorMessage" label="错误" min-width="160" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="数据预览" name="preview">
        <div class="preview-head">
          <el-select v-model="previewNode" size="small" placeholder="选择节点" style="width:200px"><el-option v-for="o in previewOptions" :key="o.key" :label="o.name" :value="o.key" /></el-select>
          <span class="muted">每个节点最多采样前 100 行（运行测试与正式运行一致）。</span>
        </div>
        <el-table v-if="previewNode" v-loading="previewLoading" :data="previewData.rows" size="small" max-height="200" empty-text="无样本">
          <el-table-column v-for="c in previewData.columns" :key="c.prop" :prop="c.prop" :label="c.label" min-width="120" show-overflow-tooltip />
        </el-table>
        <div v-else class="console-empty">{{ previewKeys.length ? '选择一个节点查看数据样本。' : '暂无可预览的节点样本。' }}</div>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<style scoped>
.console{display:flex;flex-direction:column;min-height:0;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:8px;height:220px}
.console.expanded{position:fixed;inset:24px;z-index:2900;height:auto;box-shadow:0 12px 48px rgba(0,0,0,.25)}
.console-bar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:6px 12px;border-bottom:1px solid var(--el-border-color-lighter);flex-wrap:wrap}
.console-summary{display:flex;align-items:center;gap:10px;font-size:12px;color:#5a6b82;flex-wrap:wrap}
.console-icon{width:16px;height:16px;fill:none;stroke:#2f7fd1;stroke-width:1.6}
.sum-item strong{color:#24344d}
.muted{color:#9aa4b2}
.console-actions{display:flex;align-items:center;gap:4px}
.console-tabs{flex:1;min-height:0;display:flex;flex-direction:column;padding:0 12px}
.console-tabs :deep(.el-tabs__content){flex:1;min-height:0;overflow:auto}
.log-filters{display:flex;gap:6px;align-items:center;margin-bottom:6px;flex-wrap:wrap}
.log-body{font-family:ui-monospace,Menlo,monospace;font-size:12px}
.log-line{display:flex;gap:6px;padding:1px 0;color:#5a6b82}
.log-line.error{color:#e05656}
.log-line.warn{color:#d98a2b}
.log-time{color:#9aa4b2;flex:none}
.log-level{flex:none;width:52px}
.log-node{color:#2f7fd1;flex:none}
.log-msg{word-break:break-all}
.console-empty{color:#9aa4b2;font-size:12px;padding:16px;text-align:center}
.preview-head{display:flex;gap:10px;align-items:center;margin-bottom:6px;font-size:12px;flex-wrap:wrap}
</style>