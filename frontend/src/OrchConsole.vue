<script setup>
import { computed, ref, watch } from 'vue'
import { previewTable } from './orch-graph.js'
import * as api from './orch-api.js'

const props = defineProps({
  mode: { type: String, default: 'idle' }, run: Object, logs: Array, nodeStates: Array,
  previews: Object, runId: [Number, String], nodeNames: Object, busy: Boolean, canExecute: Boolean, canRetry: Boolean
})
const emit = defineEmits(['stop', 'refresh', 'retry', 'locate', 'download'])
const tab = ref('logs')
const logNode = ref('')
const logLevel = ref('')
const logSearch = ref('')
const previewNode = ref('')

const STATE_LABELS = { PENDING: '排队', RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', INTERRUPTED: '已中断', SKIPPED: '跳过', CANCELED: '已取消' }
const STATE_TYPE = { SUCCEEDED: 'success', FAILED: 'danger', INTERRUPTED: 'warning', RUNNING: 'primary', SKIPPED: 'info', PENDING: 'info' }
const stateText = s => STATE_LABELS[s] || s || '—'
const fmtDur = ms => ms == null ? '—' : (ms >= 1000 ? (ms / 1000).toFixed(1) + ' s' : ms + ' ms')
const dotClass = s => s === 'SUCCEEDED' ? 'ok' : s === 'FAILED' ? 'bad' : (s === 'RUNNING' || s === 'PENDING') ? 'run' : s === 'INTERRUPTED' ? 'warn' : 'idle'

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
  const base = props.mode === 'test' ? previewTable(props.previews[previewNode.value]) : previewTable(runPreview.value)
  const rows = base.rows || []
  // 全数值列右对齐 + 等宽字，提升扫读性
  const columns = (base.columns || []).map(c => ({ ...c, numeric: rows.length > 0 && rows.every(r => r[c.prop] == null || typeof r[c.prop] === 'number') }))
  return { columns, rows }
})
// 数据预览默认选中第一个有样本的节点；切换运行/模式时重置，手动选择在其仍有效时保留
watch([previewOptions, () => props.runId, () => props.mode], ([opts, runId, mode], old) => {
  if (!opts.length) { previewNode.value = ''; return }
  if (runId !== old?.[1] || mode !== old?.[2] || !opts.some(o => o.key === previewNode.value)) previewNode.value = opts[0].key
}, { immediate: true })
// immediate：默认选中在 setup 期间已完成，非 immediate 会漏掉首次挂载的取数（运行记录弹框首开无样本的根因）
let previewSeq = 0
watch([previewNode, () => props.mode, () => props.runId], async ([node, mode, runId]) => {
  const seq = ++previewSeq
  runPreview.value = null
  if (mode !== 'run' || !node || !runId) return
  previewLoading.value = true
  try {
    const data = await api.fetchRunNodePreview(runId, node)
    if (seq === previewSeq) runPreview.value = data
  } catch { if (seq === previewSeq) runPreview.value = null }
  finally { if (seq === previewSeq) previewLoading.value = false }
}, { immediate: true })
function fmtTime(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '—' }
function downloadLogs() {
  const text = filteredLogs.value.map(l => `${fmtTime(l.createdAt)} [${l.level}] ${l.nodeKey || ''} ${l.message}`).join('\n')
  emit('download', text)
}
// 供无画布场景（运行记录弹框）复用：点节点行跳该节点的数据预览
function focusNode(key) {
  if (previewOptions.value.some(o => o.key === key)) { tab.value = 'preview'; previewNode.value = key }
}
defineExpose({ focusNode })
</script>

<template>
  <section class="console">
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
      </div>
    </div>
    <el-tabs v-model="tab" class="console-tabs">
      <el-tab-pane label="运行日志" name="logs">
        <div class="log-filters">
          <el-select v-model="logNode" size="small" clearable placeholder="全部节点" style="width:150px"><el-option v-for="n in nodeOptions" :key="n.key" :label="n.name" :value="n.key" /></el-select>
          <el-select v-model="logLevel" size="small" clearable placeholder="级别" style="width:100px"><el-option label="INFO" value="INFO" /><el-option label="WARN" value="WARN" /><el-option label="ERROR" value="ERROR" /></el-select>
          <el-input v-model="logSearch" size="small" clearable placeholder="搜索日志" style="width:180px" />
          <el-button size="small" text class="log-download" :disabled="!filteredLogs.length" @click="downloadLogs">下载</el-button>
        </div>
        <div class="log-body">
          <div v-if="!filteredLogs.length" class="console-empty">无日志</div>
          <div v-for="l in filteredLogs" :key="l.id || l.lineNo" class="log-line" :class="(l.level || 'INFO').toLowerCase()">
            <span class="log-time">{{ fmtTime(l.createdAt) }}</span>
            <span class="log-level">[{{ l.level }}]</span>
            <span v-if="l.nodeKey" class="log-node">{{ l.nodeKey }}</span>
            <span class="log-msg">{{ l.message }}</span>
          </div>
          <div v-if="mode === 'run' && run?.state === 'RUNNING'" class="log-live">实时追加中…</div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="节点状态" name="nodes">
        <div class="ns-head"><span>{{ (nodeStates || []).length }} 个节点</span><span class="ns-tip">点击行定位到画布</span></div>
        <div v-if="!(nodeStates || []).length" class="console-empty">尚无节点状态，运行测试或运行后生成。</div>
        <div v-else class="ns-list">
          <div v-for="row in nodeStates" :key="row.nodeKey" class="ns-row" role="button" tabindex="0"
               @click="emit('locate', row.nodeKey)" @keydown.enter="emit('locate', row.nodeKey)">
            <span class="ns-dot" :class="dotClass(row.state)"></span>
            <div class="ns-name"><strong>{{ row.nodeName || nodeNames?.[row.nodeKey] || row.nodeKey }}</strong><span class="ns-key">{{ row.nodeKey }}</span></div>
            <div class="ns-metrics">
              <span class="ns-m"><label>入</label><b>{{ row.rowsIn ?? '—' }}</b></span>
              <span class="ns-m"><label>出</label><b>{{ row.rowsOut ?? '—' }}</b></span>
              <span class="ns-m"><label>尝试</label><b>{{ row.attempt ?? '—' }}</b></span>
              <span class="ns-m"><label>耗时</label><b>{{ fmtDur(row.durationMs) }}</b></span>
            </div>
            <el-tag size="small" :type="STATE_TYPE[row.state] || 'info'">{{ stateText(row.state) }}</el-tag>
            <div v-if="row.errorMessage" class="ns-err">{{ row.errorMessage }}</div>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="数据预览" name="preview">
        <div class="preview-head">
          <el-select v-model="previewNode" size="small" placeholder="选择节点" style="width:200px"><el-option v-for="o in previewOptions" :key="o.key" :label="o.name" :value="o.key" /></el-select>
          <span class="muted">{{ previewNode ? `采样 ${previewData.rows.length} 行 · 每节点上限 100 行` : '每个节点最多采样前 100 行（运行测试与正式运行一致）。' }}</span>
        </div>
        <div v-if="previewNode" class="pv-wrap">
          <el-table v-loading="previewLoading" :data="previewData.rows" size="small" max-height="360" stripe empty-text="无样本">
            <el-table-column v-for="c in previewData.columns" :key="c.prop" :prop="c.prop" :label="c.label" min-width="120" show-overflow-tooltip :class-name="c.numeric ? 'pv-num' : ''" :label-class-name="c.numeric ? 'pv-num' : ''" />
          </el-table>
        </div>
        <div v-else class="console-empty">{{ previewKeys.length ? '选择一个节点查看数据样本。' : '暂无可预览的节点样本。' }}</div>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<style scoped>
/* 嵌入运行控制台弹框：擑满 dialog body，边框与圆角由弹框提供 */
.console{display:flex;flex-direction:column;min-height:0;height:100%;background:transparent;border:0;border-radius:0}
.console-bar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:6px 12px;border-bottom:1px solid var(--el-border-color-lighter);flex-wrap:wrap}
.console-summary{display:flex;align-items:center;gap:10px;font-size:12px;color:#5a6b82;flex-wrap:wrap}
.console-icon{width:16px;height:16px;fill:none;stroke:#2f7fd1;stroke-width:1.6}
.sum-item strong{color:#24344d}
.muted{color:#9aa4b2}
.console-actions{display:flex;align-items:center;gap:4px}
.console-tabs{flex:1;min-height:0;display:flex;flex-direction:column;padding:0 12px}
.console-tabs :deep(.el-tabs__content){flex:1;min-height:0;overflow:hidden}
.console-tabs :deep(.el-tab-pane){height:100%;display:flex;flex-direction:column;overflow:auto}
.log-filters{display:flex;gap:6px;align-items:center;margin-bottom:8px;flex-wrap:wrap}
.log-download{margin-left:auto}
/* 深色终端日志舱：控制台唯一的视觉强调点（视觉伴侣方向 B） */
.log-body{flex:1;min-height:0;overflow:auto;background:#101826;border-radius:8px;padding:8px 10px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;line-height:1.9}
.log-body::-webkit-scrollbar{width:8px}
.log-body::-webkit-scrollbar-thumb{background:#2a3648;border-radius:4px}
.log-body::-webkit-scrollbar-track{background:transparent}
.log-line{display:flex;gap:8px;color:#c6d2e2}
.log-time{color:#8494ab;flex:none}
.log-level{flex:none;width:46px;color:#8fb3d9}
.log-line.warn .log-level{color:#e2b04a}
.log-line.error .log-level{color:#ef8080}
.log-node{color:#6ea8fe;flex:none}
.log-msg{word-break:break-all}
.log-live{color:#8494ab;font-size:11px;padding-top:4px}
.log-body .console-empty{color:#8494ab}
.console-empty{color:#9aa4b2;font-size:12px;padding:16px;text-align:center}
.preview-head{display:flex;gap:10px;align-items:center;margin-bottom:8px;font-size:12px;flex-wrap:wrap}
/* 节点状态：卡片式执行行，指标分组、错误行内展开 */
.ns-head{display:flex;justify-content:space-between;align-items:center;font-size:11px;color:#8a94a6;margin-bottom:8px}
.ns-tip{color:#b6c2cf}
.ns-list{flex:1;min-height:0;overflow:auto;display:flex;flex-direction:column;gap:8px}
.ns-row{display:grid;grid-template-columns:auto 1fr auto auto;gap:4px 12px;align-items:center;border:1px solid #eef2f7;border-radius:8px;padding:8px 12px;cursor:pointer;background:#fff}
.ns-row:hover{border-color:#b3d4fc;background:#f8fbff}
.ns-row:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:1px}
.ns-dot{width:8px;height:8px;border-radius:50%;background:#c3c9d1}
.ns-dot.ok{background:#2f9e57}.ns-dot.bad{background:#e05656}.ns-dot.warn{background:#d98a2b}
.ns-dot.run{background:#2f7fd1;animation:ns-pulse 1.2s ease-in-out infinite}
@keyframes ns-pulse{0%,100%{opacity:1}50%{opacity:.35}}
@media (prefers-reduced-motion: reduce){.ns-dot.run{animation:none}}
.ns-name{min-width:0;display:flex;align-items:baseline;gap:8px}
.ns-name strong{font-size:13px;color:#24344d;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.ns-key{font-family:ui-monospace,Menlo,monospace;font-size:11px;color:#9aa4b2;flex:none}
.ns-metrics{display:flex;gap:14px}
.ns-m label{font-size:11px;color:#9aa4b2;margin-right:4px}
.ns-m b{font-size:12px;color:#24344d;font-family:ui-monospace,Menlo,monospace;font-weight:600}
.ns-err{grid-column:1/-1;font-size:12px;color:#e05656;background:#fff2f0;border-radius:6px;padding:4px 8px;word-break:break-all}
@media(max-width:640px){.ns-row{grid-template-columns:auto 1fr auto}.ns-metrics{grid-column:2/-1}}
/* 数据预览：表头收敛、斑马纹、数值列右对齐等宽 */
.pv-wrap{border:1px solid #eef2f7;border-radius:8px;overflow:hidden}
.pv-wrap :deep(.el-table__header th){background:#f8fafc;color:#8a94a6;font-size:11px;font-weight:600}
.pv-wrap :deep(.el-table__body td){font-size:12px;color:#5a6b82}
.pv-wrap :deep(.pv-num){font-family:ui-monospace,Menlo,monospace;text-align:right}
.pv-wrap :deep(.el-table__body tr.el-table__row--striped td.el-table__cell){background:#fafcfe}
</style>