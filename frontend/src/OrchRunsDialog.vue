<script setup>
import { ref, computed, watch, inject } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from './orch-api.js'
import { previewTable } from './orch-graph.js'
import RefreshIcon from './RefreshIcon.vue'

const props = defineProps({ open: Boolean, task: Object })
const emit = defineEmits(['update:open', 'changed'])
const can = inject('canPermission', () => false)
const runs = ref([]), loading = ref(false)
const detail = ref(null), detailLoading = ref(false), detailTab = ref('nodes')
const logs = ref([]), logNode = ref(''), logLevel = ref(''), logSearch = ref('')
const previewNode = ref(''), runPreview = ref(null), previewLoading = ref(false)
const previewKeys = computed(() => (detail.value?.nodes || []).filter(n => n.hasSample).map(n => n.nodeKey))
const previewOptions = computed(() => (detail.value?.nodes || []).filter(n => n.hasSample).map(n => ({ key: n.nodeKey, name: n.nodeName || n.nodeKey })))
const previewData = computed(() => previewTable(runPreview.value))
watch(previewNode, async node => {
  runPreview.value = null
  if (!node || !detail.value) return
  previewLoading.value = true
  try { runPreview.value = await api.fetchRunNodePreview(detail.value.id, node) } catch { runPreview.value = null } finally { previewLoading.value = false }
})
const STATE = { PENDING: '排队', RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', INTERRUPTED: '已中断', SKIPPED: '跳过', CANCELED: '已取消' }
const TYPE = { SUCCEEDED: 'success', FAILED: 'danger', INTERRUPTED: 'warning', RUNNING: 'primary', PENDING: 'info', SKIPPED: 'info' }
const fmt = v => v ? String(v).replace('T', ' ').slice(0, 19) : '—'
const duration = computed(() => {
  const d = detail.value
  if (!d?.startedAt) return '—'
  const end = d.finishedAt ? new Date(d.finishedAt) : new Date()
  const ms = end - new Date(d.startedAt)
  return ms >= 0 ? (ms / 1000).toFixed(1) + ' s' : '—'
})
const nodeOptions = computed(() => (detail.value?.nodes || []).map(n => ({ key: n.nodeKey, name: n.nodeName || n.nodeKey })))
const filteredLogs = computed(() => {
  const kw = logSearch.value.trim().toLowerCase()
  return logs.value.filter(l =>
    (!logNode.value || l.nodeKey === logNode.value) &&
    (!logLevel.value || l.level === logLevel.value) &&
    (!kw || String(l.message || '').toLowerCase().includes(kw)))
})

async function load() {
  if (!props.task) return
  loading.value = true
  try { runs.value = await api.fetchRuns(props.task.id) } catch (e) { ElMessage.error(e.message) } finally { loading.value = false }
}
async function loadLogs(runId) {
  try { logs.value = await api.fetchRunLogs(runId, { afterId: 0, limit: 1000 }) } catch { logs.value = [] }
}
async function showDetail(row) {
  detailLoading.value = true
  logNode.value = ''; logLevel.value = ''; logSearch.value = ''; previewNode.value = ''; runPreview.value = null
  try {
    detail.value = await api.fetchRunDetail(row.id)
    await loadLogs(row.id)
  } catch (e) { ElMessage.error(e.message) } finally { detailLoading.value = false }
}
async function refreshDetail() {
  if (!detail.value) return
  detailLoading.value = true
  try { detail.value = await api.fetchRunDetail(detail.value.id); await loadLogs(detail.value.id) } catch (e) { ElMessage.error(e.message) } finally { detailLoading.value = false }
}
async function retry(row) {
  try { await ElMessageBox.confirm('重试可能重复写入，确认已检查幂等策略？', '重试', { type: 'warning' }) } catch { return }
  try { await api.retryRun(props.task.id, row.id); ElMessage.success('已发起重试'); await load(); if (detail.value?.id === row.id) await refreshDetail(); emit('changed') } catch (e) { ElMessage.error(e.message) }
}
async function stop() {
  try { await ElMessageBox.confirm('终止会中断运行，可能已写入部分数据。确定终止？', '终止执行', { type: 'warning' }) } catch { return }
  try { await api.stopTask(props.task.id); ElMessage.success('已请求终止'); await load(); await refreshDetail(); emit('changed') } catch (e) { ElMessage.error(e.message) }
}
function downloadLogs() {
  const text = filteredLogs.value.map(l => `${fmt(l.createdAt)} [${l.level}] ${l.nodeKey || ''} ${l.message}`).join('\n')
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url; a.download = `run-${detail.value?.id}-logs.txt`; a.click()
  URL.revokeObjectURL(url)
}
watch(() => props.open, open => { if (open) { detail.value = null; logs.value = []; load() } })
</script>

<template>
  <el-dialog :model-value="open" :title="`${task?.name || ''} · 运行记录`" width="min(1000px,96vw)" @update:model-value="emit('update:open', $event)">
    <div class="runs-toolbar"><el-button class="refresh-button" title="刷新" @click="load"><RefreshIcon /></el-button></div>
    <el-table :data="runs" size="small" v-loading="loading" empty-text="暂无运行记录" max-height="280" highlight-current-row @row-click="showDetail">
      <el-table-column prop="id" label="实例" width="70" />
      <el-table-column prop="taskVersion" label="版本" width="60" />
      <el-table-column prop="businessDate" label="业务日期" width="110" />
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" :type="TYPE[row.state] || 'info'">{{ STATE[row.state] || row.state }}</el-tag></template></el-table-column>
      <el-table-column label="开始" min-width="150"><template #default="{ row }">{{ fmt(row.startedAt) }}</template></el-table-column>
      <el-table-column label="结束" min-width="150"><template #default="{ row }">{{ fmt(row.finishedAt) }}</template></el-table-column>
      <el-table-column label="尝试" min-width="140"><template #default="{ row }"><span v-for="a in row.attempts" :key="a.attempt" class="attempt">第{{ a.attempt }}次 {{ STATE[a.state] || a.state }} </span></template></el-table-column>
      <el-table-column label="操作" width="130"><template #default="{ row }">
        <el-button text type="primary" size="small" @click.stop="showDetail(row)">详情</el-button>
        <el-button v-if="can('workflow.task.retry') && ['FAILED', 'INTERRUPTED'].includes(row.state)" text type="warning" size="small" @click.stop="retry(row)">重试</el-button>
      </template></el-table-column>
    </el-table>

    <div v-if="detail" class="run-detail" v-loading="detailLoading">
      <div class="detail-head">
        <div class="detail-summary">
          <strong>实例 #{{ detail.id }}</strong>
          <el-tag size="small" :type="TYPE[detail.state] || 'info'">{{ STATE[detail.state] || detail.state }}</el-tag>
          <span>业务日期 {{ detail.businessDate }}</span>
          <span>耗时 {{ duration }}</span>
          <span>开始 {{ fmt(detail.startedAt) }}</span>
        </div>
        <div class="detail-actions">
          <el-button v-if="detail.state === 'RUNNING' && can('workflow.task.execute')" size="small" type="warning" @click="stop">终止</el-button>
          <el-button v-if="['FAILED', 'INTERRUPTED'].includes(detail.state) && can('workflow.task.retry')" size="small" type="warning" @click="retry(detail)">重试</el-button>
          <el-button size="small" @click="refreshDetail">刷新</el-button>
        </div>
      </div>
      <el-tabs v-model="detailTab">
        <el-tab-pane label="节点状态" name="nodes">
          <el-table :data="detail.nodes" size="small" max-height="240" empty-text="无节点状态">
            <el-table-column label="节点" min-width="120"><template #default="{ row }">{{ row.nodeName || row.nodeKey }}</template></el-table-column>
            <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" :type="TYPE[row.state] || 'info'">{{ STATE[row.state] || row.state }}</el-tag></template></el-table-column>
            <el-table-column prop="attempt" label="尝试" width="60" />
            <el-table-column prop="rowsIn" label="入行数" width="80" />
            <el-table-column prop="rowsOut" label="出行数" width="80" />
            <el-table-column label="耗时" width="90"><template #default="{ row }">{{ row.durationMs != null ? row.durationMs + ' ms' : '—' }}</template></el-table-column>
            <el-table-column prop="errorMessage" label="错误" min-width="160" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="运行日志" name="logs">
          <div class="log-filters">
            <el-select v-model="logNode" size="small" clearable placeholder="全部节点" style="width:150px"><el-option v-for="n in nodeOptions" :key="n.key" :label="n.name" :value="n.key" /></el-select>
            <el-select v-model="logLevel" size="small" clearable placeholder="级别" style="width:100px"><el-option label="INFO" value="INFO" /><el-option label="WARN" value="WARN" /><el-option label="ERROR" value="ERROR" /></el-select>
            <el-input v-model="logSearch" size="small" clearable placeholder="搜索日志" style="width:180px" />
            <el-button size="small" text :disabled="!filteredLogs.length" @click="downloadLogs">下载</el-button>
            <span class="log-count">{{ filteredLogs.length }} 条</span>
          </div>
          <div class="log-body">
            <div v-if="!filteredLogs.length" class="log-empty">无日志</div>
            <div v-for="l in filteredLogs" :key="l.id" class="log-line" :class="(l.level || 'INFO').toLowerCase()">
              <span class="log-time">{{ fmt(l.createdAt) }}</span>
              <span class="log-level">[{{ l.level }}]</span>
              <span v-if="l.nodeKey" class="log-node">{{ l.nodeKey }}</span>
              <span class="log-msg">{{ l.message }}</span>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="数据预览" name="preview">
          <div class="log-filters">
            <el-select v-model="previewNode" size="small" placeholder="选择节点" style="width:200px"><el-option v-for="o in previewOptions" :key="o.key" :label="o.name" :value="o.key" /></el-select>
            <span class="log-count">每节点最多采样前 100 行</span>
          </div>
          <el-table v-if="previewNode" v-loading="previewLoading" :data="previewData.rows" size="small" max-height="260" empty-text="无样本">
            <el-table-column v-for="c in previewData.columns" :key="c.prop" :prop="c.prop" :label="c.label" min-width="120" show-overflow-tooltip />
          </el-table>
          <div v-else class="log-empty">{{ previewKeys.length ? '选择一个节点查看数据样本。' : '该运行暂无可预览的节点样本。' }}</div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-dialog>
</template>

<style scoped>
.runs-toolbar{display:flex;justify-content:flex-end;margin-bottom:8px}
.attempt{font-size:12px;color:#5a6b82;margin-right:6px}
.run-detail{margin-top:14px;border-top:1px solid var(--el-border-color-lighter);padding-top:12px}
.run-detail :deep(.el-tabs__content .el-tab-pane){min-height:0;padding:0;border:0;border-radius:0;box-shadow:none;background:transparent}
.detail-head{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:8px}
.detail-summary{display:flex;align-items:center;gap:10px;flex-wrap:wrap;font-size:12px;color:#5a6b82}
.detail-summary strong{font-size:13px;color:#24344d}
.detail-actions{display:flex;gap:6px}
.log-filters{display:flex;gap:6px;align-items:center;margin-bottom:8px;flex-wrap:wrap}
.log-count{font-size:12px;color:#9aa4b2;margin-left:auto}
.log-body{font-family:ui-monospace,Menlo,monospace;font-size:12px;max-height:260px;overflow:auto;background:#fafbfc;border:1px solid var(--el-border-color-lighter);border-radius:6px;padding:8px}
.log-line{display:flex;gap:6px;padding:1px 0;color:#5a6b82}
.log-line.error{color:#e05656}
.log-line.warn{color:#d98a2b}
.log-time{color:#9aa4b2;flex:none}
.log-level{flex:none;width:52px}
.log-node{color:#2f7fd1;flex:none}
.log-msg{word-break:break-all}
.log-empty{color:#9aa4b2;text-align:center;padding:16px}
</style>