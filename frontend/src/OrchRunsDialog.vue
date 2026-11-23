<script setup>
import { ref, computed, watch, inject, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from './orch-api.js'
import { usePagination } from './pagination.js'
import OrchConsole from './OrchConsole.vue'
import RefreshIcon from './RefreshIcon.vue'

const props = defineProps({ open: Boolean, task: Object })
const emit = defineEmits(['update:open', 'changed'])
const can = inject('canPermission', () => false)
const runs = ref([]), loading = ref(false)
const paging = usePagination(runs)
// 详情复用画布控制台组件（视觉伴侣方案 A：双层弹框钻取）
const detail = ref(null), detailLoading = ref(false), consoleOpen = ref(false), consoleRef = ref(null)
const logs = ref([])
let lastLogId = 0
const nodeNames = computed(() => Object.fromEntries((detail.value?.nodes || []).map(n => [n.nodeKey, n.nodeName || n.nodeKey])))
const STATE = { PENDING: '排队', RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', INTERRUPTED: '已中断', SKIPPED: '跳过', CANCELED: '已取消' }
const TYPE = { SUCCEEDED: 'success', FAILED: 'danger', INTERRUPTED: 'warning', RUNNING: 'primary', PENDING: 'info', SKIPPED: 'info' }
const fmt = v => v ? String(v).replace('T', ' ').slice(0, 19) : '—'

async function load() {
  if (!props.task) return
  loading.value = true
  try { runs.value = await api.fetchRuns(props.task.id) } catch (e) { ElMessage.error(e.message) } finally { loading.value = false }
}
async function loadLogsIncremental() {
  try {
    const rows = await api.fetchRunLogs(detail.value.id, { afterId: lastLogId, limit: 1000 })
    if (rows.length) { logs.value.push(...rows); lastLogId = rows[rows.length - 1].id }
  } catch { /* 轮询瞬时失败忽略 */ }
}
async function showDetail(row) {
  detailLoading.value = true
  logs.value = []; lastLogId = 0
  try {
    detail.value = await api.fetchRunDetail(row.id)
    await loadLogsIncremental()
    consoleOpen.value = true
  } catch (e) { ElMessage.error(e.message) } finally { detailLoading.value = false }
}
async function refreshDetail() {
  if (!detail.value || detailLoading.value) return
  detailLoading.value = true
  try { detail.value = await api.fetchRunDetail(detail.value.id); await loadLogsIncremental() } catch { /* 瞬时 */ } finally { detailLoading.value = false }
}
// 控制台内点节点行：跳该节点的数据预览（无画布可定位）
function consoleLocate(key) { consoleRef.value?.focusNode(key) }
async function retry(row) {
  try { await ElMessageBox.confirm('重试可能重复写入，确认已检查幂等策略？', '重试', { type: 'warning' }) } catch { return }
  try { await api.retryRun(props.task.id, row.id); ElMessage.success('已发起重试'); await load(); if (detail.value?.id === row.id) await refreshDetail(); emit('changed') } catch (e) { ElMessage.error(e.message) }
}
async function stop() {
  try { await ElMessageBox.confirm('终止会中断运行，可能已写入部分数据。确定终止？', '终止执行', { type: 'warning' }) } catch { return }
  try { await api.stopTask(props.task.id); ElMessage.success('已请求终止'); await load(); await refreshDetail(); emit('changed') } catch (e) { ElMessage.error(e.message) }
}
function downloadLogs(text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url; a.download = `run-${detail.value?.id}-logs.txt`; a.click()
  URL.revokeObjectURL(url)
}
watch(() => props.open, open => {
  if (open) { detail.value = null; logs.value = []; lastLogId = 0; consoleOpen.value = false; paging.page.value = 1; load() }
  else consoleOpen.value = false
})
// 运行中增量轮询，与画布控制台同节奏（2s）
let pollTimer = null
function stopPoll() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } }
watch([consoleOpen, () => detail.value?.state], ([open, state]) => {
  stopPoll()
  if (open && (state === 'RUNNING' || state === 'PENDING')) pollTimer = setInterval(refreshDetail, 2000)
})
onBeforeUnmount(stopPoll)
</script>

<template>
  <el-dialog class="orch-runs-dialog" :model-value="open" :title="`${task?.name || ''} · 运行记录`" width="min(1000px,96vw)" @update:model-value="emit('update:open', $event)">
    <div class="runs-toolbar"><el-button class="refresh-button" title="刷新" @click="load"><RefreshIcon /></el-button></div>
    <el-table :data="paging.visible.value" size="small" v-loading="loading" empty-text="暂无运行记录" max-height="280" highlight-current-row @row-click="showDetail">
      <el-table-column prop="id" label="实例" width="70" class-name="run-num" />
      <el-table-column prop="taskVersion" label="版本" width="60" class-name="run-num" />
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" :type="TYPE[row.state] || 'info'">{{ STATE[row.state] || row.state }}</el-tag></template></el-table-column>
      <el-table-column label="开始" min-width="160" class-name="run-time"><template #default="{ row }">{{ fmt(row.startedAt) }}</template></el-table-column>
      <el-table-column label="结束" min-width="160" class-name="run-time"><template #default="{ row }">{{ fmt(row.finishedAt) }}</template></el-table-column>
      <el-table-column label="尝试" min-width="150"><template #default="{ row }"><span v-for="a in row.attempts" :key="a.attempt" class="attempt">第 {{ a.attempt }} 次 · {{ STATE[a.state] || a.state }}</span></template></el-table-column>
      <el-table-column label="操作" width="130"><template #default="{ row }">
        <el-button text type="primary" size="small" @click.stop="showDetail(row)">详情</el-button>
        <el-button v-if="can('workflow.task.retry') && ['FAILED', 'INTERRUPTED'].includes(row.state)" text type="warning" size="small" @click.stop="retry(row)">重试</el-button>
      </template></el-table-column>
    </el-table>
    <div class="runs-pagination"><el-pagination v-model:current-page="paging.page.value" v-model:page-size="paging.pageSize.value" :total="paging.total.value" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next"/></div>
  </el-dialog>

  <!-- 第二层：复用画布运行控制台（深色终端日志/节点卡片/数据预览） -->
  <el-dialog v-model="consoleOpen" :title="`运行 #${detail?.id ?? ''} · 运行控制台`" width="min(1080px,92vw)"
             class="orch-console-dialog" append-to-body :close-on-click-modal="false">
    <OrchConsole v-if="detail" ref="consoleRef" mode="run" :run="detail" :logs="logs" :node-states="detail.nodes || []"
                 :previews="{}" :run-id="detail.id" :node-names="nodeNames" :busy="detailLoading"
                 :can-execute="can('workflow.task.execute')" :can-retry="can('workflow.task.retry')"
                 @stop="stop" @refresh="refreshDetail" @retry="retry(detail)" @locate="consoleLocate" @download="downloadLogs" />
  </el-dialog>
</template>

<style scoped>
.runs-toolbar{display:flex;justify-content:flex-end;margin-bottom:8px}
.runs-pagination{display:flex;justify-content:flex-end;margin-top:10px}
.attempt{font-size:12px;color:#5a6b82}
.attempt + .attempt{margin-left:8px}
</style>

<style>
/* 运行记录弹框排版统一：覆盖全局表格居中/控制字号，仅作用于本弹框 */
.orch-runs-dialog.el-dialog .el-table th .cell,.orch-runs-dialog.el-dialog .el-table td .cell{text-align:left}
.orch-runs-dialog.el-dialog .el-table th.el-table__cell{height:36px;background:#f8fafc;color:#8a94a6;font-size:11px;font-weight:600}
.orch-runs-dialog.el-dialog .el-table td.el-table__cell{height:38px}
.orch-runs-dialog.el-dialog .el-table .cell{font-size:12px;color:#5a6b82}
.orch-runs-dialog .run-num,.orch-runs-dialog .run-time{font-family:ui-monospace,Menlo,Consolas,monospace}
.orch-runs-dialog .run-time{white-space:nowrap}
/* 运行控制台弹框外壳（画布与运行记录共用，随 OrchConsole 加载） */
.orch-console-dialog .el-dialog__body{height:min(66vh,720px);padding:10px 16px 14px;overflow:hidden;display:flex;flex-direction:column}
.orch-console-dialog .el-dialog__body > .console{flex:1;min-height:0}
.orch-console-dialog .el-dialog__header{padding:14px 16px;margin-right:0;border-bottom:1px solid var(--el-border-color-lighter)}
.orch-console-dialog .el-dialog__title{font-size:15px;font-weight:600;color:#24344d}
</style>
