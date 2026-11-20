<script setup>
import { computed, inject, ref, watch } from 'vue'
import { authorizedRequest } from './auth.js'
import { emptyTask, formFromTask, configurationFromForm, newParameter } from './task-edit.js'
const props = defineProps({ open: Boolean, task: Object, sources: Array, directories: Array, readonly: Boolean })
const emit = defineEmits(['update:open', 'saved'])
const can = inject('canPermission', () => false)
// 复制预填时 task.id 为 null，按新增处理
const editing = computed(() => !!props.task?.id)
const form = ref(emptyTask()), directoryId = ref(null), description = ref(''), error = ref(''), busy = ref(false), prefillFailed = ref(false)
watch(() => props.open, open => {
  if (!open) return
  error.value = ''; prefillFailed.value = false
  if (props.task) {
    try { form.value = formFromTask(props.task) } catch (e) { error.value = e.message; form.value = emptyTask(); prefillFailed.value = true }
    directoryId.value = props.task.directoryId ?? null
    description.value = props.task.description ?? ''
  } else { form.value = emptyTask(); directoryId.value = null; description.value = '' }
}, { immediate: true })
// 复制态手动切换类型（与源任务不同）时丢弃异类原始配置，避免 SQL↔SYNC 残留字段
watch(() => form.value.type, () => { if (!editing.value && props.task && form.value.type !== props.task.type) form.value.originalConfiguration = {} })
async function save() {
  if (busy.value || prefillFailed.value) return
  error.value = ''; busy.value = true
  try {
    const configuration = configurationFromForm(form.value)
    const metadata = { name: form.value.name, directoryId: directoryId.value, description: description.value }
    if (editing.value) {
      const put = await authorizedRequest(`/api/tasks/${props.task.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(metadata) })
      if (!put.ok) throw new Error(put.status === 403 ? '当前账号没有任务修改权限' : put.status === 409 ? '任务运行中，不能修改' : '基本信息保存失败')
      const response = await authorizedRequest(`/api/tasks/${props.task.id}/versions`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ configuration }) })
      if (!response.ok) throw new Error(response.status === 409 ? '任务运行中，配置未保存（基本信息已保存）' : response.status === 403 ? '当前账号没有任务修改权限（基本信息已保存）' : '配置保存失败，请检查表单（基本信息已保存）')
    } else {
      const response = await authorizedRequest('/api/tasks', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...metadata, type: form.value.type, configuration }) })
      if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有任务创建权限' : '任务保存失败，请检查表单')
    }
    emit('update:open', false); emit('saved')
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
</script>
<template>
  <el-dialog :model-value="open" :title="readonly ? '查看任务' : editing ? '编辑任务（保存将创建新版本）' : '新增任务'" width="min(850px, 95vw)" destroy-on-close :show-close="!busy" :close-on-click-modal="false" :close-on-press-escape="!busy" @update:model-value="emit('update:open', $event)">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <el-form label-position="top" :disabled="readonly" @submit.prevent="save">
      <div class="grid">
        <el-form-item label="任务名称"><el-input v-model="form.name" :disabled="editing"/></el-form-item>
        <el-form-item label="任务类型"><el-select v-model="form.type" :disabled="editing"><el-option label="SQL 加工" value="SQL"/><el-option label="数据库同步" value="SYNC"/></el-select></el-form-item>
        <el-form-item label="所属目录"><el-select v-model="directoryId" clearable placeholder="未分类"><el-option v-for="d in directories" :key="d.id" :label="d.name" :value="d.id"/></el-select></el-form-item>
        <el-form-item label="描述"><el-input v-model="description" maxlength="500" placeholder="可选"/></el-form-item>
        <el-form-item label="源数据源"><el-select v-model="form.sourceId"><el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id"/></el-select></el-form-item>
        <el-form-item v-if="form.type === 'SYNC'" label="目标数据源"><el-select v-model="form.targetId"><el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id"/></el-select></el-form-item>
      </div>
      <template v-if="form.type === 'SQL'">
        <el-form-item label="SQL 语句"><el-input v-model="form.sql" type="textarea" :rows="4"/></el-form-item>
      </template>
      <template v-else>
        <el-form-item label="源查询 SQL"><el-input v-model="form.sourceQuery" type="textarea" :rows="3"/></el-form-item>
        <div class="grid">
          <el-form-item label="目标表"><el-input v-model="form.targetTable" placeholder="例如 schema.table"/></el-form-item>
          <el-form-item label="目标字段，逗号分隔"><el-input v-model="form.columns" placeholder="id, name"/></el-form-item>
          <el-form-item label="写入方式"><el-select v-model="form.writeMode"><el-option label="追加" value="APPEND"/><el-option label="按唯一键更新插入" value="UPSERT"/></el-select></el-form-item>
          <el-form-item v-if="form.writeMode === 'UPSERT'" label="目标唯一键字段，逗号分隔"><el-input v-model="form.keys" placeholder="需与目标表主键或唯一索引完全一致"/></el-form-item>
          <el-form-item label="批次大小"><el-input-number v-model="form.batchSize" :min="1" :max="10000"/></el-form-item>
        </div>
      </template>
      <div class="parameter-editor">
        <div class="row"><strong>SQL 参数</strong><el-button text type="primary" :disabled="form.parameters.length >= 1000" @click="form.parameters.push(newParameter())">添加参数</el-button></div>
        <p>按照 SQL 中占位符的顺序填写。业务日期由每次运行时传入。数字超过安全精度时请使用数据库支持的文本转换。</p>
        <div v-for="(parameter, index) in form.parameters" :key="index" class="row parameter-row">
          <span>第 {{ index + 1 }} 个</span>
          <el-select :model-value="parameter.kind" aria-label="参数类型" @change="form.parameters[index] = newParameter($event)"><el-option label="文本" value="string"/><el-option label="数字" value="number"/><el-option label="布尔" value="boolean"/><el-option label="空值" value="null"/><el-option label="业务日期" value="date"/></el-select>
          <el-input v-if="parameter.kind === 'string'" v-model="parameter.value" aria-label="文本参数"/>
          <el-input v-else-if="parameter.kind === 'number'" v-model="parameter.value" inputmode="decimal" placeholder="例如 12.5" aria-label="数字参数"/>
          <el-select v-else-if="parameter.kind === 'boolean'" v-model="parameter.value" aria-label="布尔参数"><el-option label="是" :value="true"/><el-option label="否" :value="false"/></el-select>
          <el-button text :disabled="index === 0" @click="[form.parameters[index - 1], form.parameters[index]] = [form.parameters[index], form.parameters[index - 1]]">上移</el-button>
          <el-button text :disabled="index === form.parameters.length - 1" @click="[form.parameters[index + 1], form.parameters[index]] = [form.parameters[index], form.parameters[index + 1]]">下移</el-button>
          <el-button text type="danger" @click="form.parameters.splice(index, 1)">删除</el-button>
        </div>
      </div>
      <div><el-checkbox v-model="form.retrySafe">我已确认任务在工作流中自动重试不会造成重复或错误数据</el-checkbox>
        <div v-if="form.retrySafe" class="row"><span>最多重试</span><el-input-number v-model="form.maxRetries" :min="1" :max="3"/><span>次，间隔（秒）</span><el-input-number v-model="form.retryDelay" :min="1" :max="3600"/></div>
      </div>
      <el-button v-if="!readonly && !prefillFailed && (editing ? can('workflow.task.update') : can('workflow.task.create'))" type="primary" native-type="submit" :loading="busy">{{ editing ? '保存新版本' : '保存任务' }}</el-button>
    </el-form>
    <el-button :disabled="busy" @click="emit('update:open', false)">{{ readonly ? '关闭' : '取消' }}</el-button>
  </el-dialog>
</template>
<style scoped>.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.row{display:flex;gap:12px;align-items:center;margin:14px 0}.parameter-editor p{color:#687e7b;font-size:13px}.parameter-row .el-select,.parameter-row .el-input{max-width:200px}@media(max-width:700px){.grid{grid-template-columns:1fr}.row{flex-wrap:wrap}}</style>
