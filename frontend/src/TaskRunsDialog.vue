<script setup>
import { inject, ref, watch } from 'vue'
import { authorizedRequest } from './auth.js'
import { usePagination } from './pagination.js'
import RefreshIcon from './RefreshIcon.vue'
const props = defineProps({ open: Boolean, task: Object })
const emit = defineEmits(['update:open'])
const can = inject('canPermission', () => false)
const runs = ref([]), error = ref(''), message = ref(''), retrying = ref(false)
const paging = usePagination(runs)
let loadSequence = 0
async function load() {
  message.value = ''
  if (!props.task) return
  const request = ++loadSequence
  try {
    const response = await fetch(`/api/tasks/${props.task.id}/runs`, { credentials: 'same-origin' })
    if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有任务读取权限' : '读取运行记录失败')
    const result = await response.json()
    if (request !== loadSequence) return
    runs.value = result; error.value = ''
  } catch (e) { if (request === loadSequence) error.value = e.message }
}
watch(() => props.open, open => { if (open) { runs.value = []; error.value = ''; load() } }, { immediate: true })
async function retry(run) {
  if (retrying.value) return
  retrying.value = true
  try {
    const response = await authorizedRequest(`/api/tasks/${props.task.id}/runs/${run.id}/retry`, { method: 'POST' })
    if (!response.ok) throw new Error(response.status === 409 ? '实例状态已变化，无法重试' : '重试失败，请检查权限和实例状态')
    await load(); message.value = '任务重试已提交'
  } catch (e) { error.value = e.message } finally { retrying.value = false }
}
</script>
<template>
  <el-dialog :model-value="open" :title="`${task?.name || ''} 运行记录`" width="min(1050px, 96vw)" @update:model-value="emit('update:open', $event)">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <el-alert v-if="message" :title="message" type="success" :closable="false" show-icon/>
    <div class="toolbar list-query-toolbar"><div class="list-query-controls"><span>共 {{ runs.length }} 条实例</span></div><div class="list-query-actions"><el-button class="refresh-button" title="刷新" aria-label="刷新" @click="load"><RefreshIcon /></el-button></div></div>
    <el-table :data="paging.visible.value" empty-text="暂无运行记录">
      <el-table-column prop="id" label="实例"/><el-table-column prop="taskVersion" label="版本"/><el-table-column prop="businessDate" label="业务日期"/><el-table-column prop="state" label="状态"/><el-table-column prop="startedAt" label="开始时间"/>
      <el-table-column label="执行记录" min-width="180"><template #default="scope"><div v-for="attempt in scope.row.attempts" :key="attempt.attempt">第 {{ attempt.attempt }} 次：{{ attempt.state }} {{ attempt.errorMessage || '' }}</div></template></el-table-column>
      <el-table-column label="操作"><template #default="scope"><el-popconfirm v-if="can('workflow.task.retry') && ['FAILED','INTERRUPTED'].includes(scope.row.state)" title="重试可能重复写入，确认已检查幂等策略？" @confirm="retry(scope.row)"><template #reference><el-button text type="warning" :loading="retrying">重试</el-button></template></el-popconfirm></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="paging.page.value" v-model:page-size="paging.pageSize.value" :total="paging.total.value" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next"/>
  </el-dialog>
</template>
<style scoped>.el-pagination{margin-top:18px;justify-content:flex-end}</style>
