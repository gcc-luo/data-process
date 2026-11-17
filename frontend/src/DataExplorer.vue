<script setup>
import { onMounted, ref, computed } from 'vue'
import { authorizedRequest } from './auth.js'
import { analysisQuery, changeFilterOperation, changeFilterType } from './analysis-filters.js'
import { createValidationGuard } from './workflow.js'
import { usePagination } from './pagination.js'
const datasets = ref([]), datasetId = ref(null), fields = ref([]), groups = ref([]), metricOperation = ref('COUNT'), metricField = ref(''), filters = ref([]), rows = ref([]), error = ref(''), busy = ref(false), mode = ref('preview')
const sortField = ref(''), sortDirection = ref('DESC')
const rowLimit = ref(200)
const reportName = ref(''), chartType = ref('TABLE'), lastQuery = ref(null), message = ref('')
const queryGuard = createValidationGuard()
const queryOpen = ref(false), search = ref('')
const filteredDatasets = computed(() => datasets.value.filter(item => item.name.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase())))
const { page, pageSize, total, visible } = usePagination(filteredDatasets)
const dataset = computed(() => datasets.value.find(d => d.id === datasetId.value))
async function load() { try { const response = await fetch('/api/accessible-datasets', { credentials: 'same-origin' }); if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有数据集读取权限' : '无法读取数据集'); datasets.value = await response.json() } catch (e) { error.value = e.message } }
function openDataset(item) { datasetId.value = item.id; selectDataset(); queryOpen.value = true }
function closeQuery() { queryGuard.changed(); busy.value = false; datasetId.value = null; lastQuery.value = null; rows.value = []; error.value = '' }
function selectDataset() { queryGuard.changed(); busy.value = false; fields.value = []; groups.value = []; metricField.value = ''; filters.value = []; sortField.value = ''; rows.value = []; lastQuery.value = null; error.value = ''; message.value = '' }
async function execute() {
  const requestId = queryGuard.start(), queryDatasetId = datasetId.value
  error.value = ''; rows.value = []; busy.value = true
  try {
    if (!dataset.value) throw new Error('请选择数据集')
    const analysis = mode.value === 'analysis'
    const body = analysis ? analysisQuery({ groups: groups.value, metricOperation: metricOperation.value, metricField: metricField.value, filters: filters.value, limit: rowLimit.value, sortField: sortField.value, sortDirection: sortDirection.value }) : { fields: [...fields.value], limit: rowLimit.value }
    const response = await authorizedRequest(`/api/datasets/${queryDatasetId}/${analysis ? 'analysis' : 'preview'}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    if (!response.ok) throw new Error(response.status === 403 ? '无权查询所选字段，脱敏字段不能用于筛选或聚合' : '查询失败，请检查字段、条件和数据库连接')
    const result = await response.json()
    if (!queryGuard.isCurrent(requestId)) return
    rows.value = result; lastQuery.value = { datasetId: queryDatasetId, kind: analysis ? 'ANALYSIS' : 'PREVIEW', configuration: body }; message.value = ''
  } catch (e) { if (queryGuard.isCurrent(requestId)) error.value = e.message } finally { if (queryGuard.isCurrent(requestId)) busy.value = false }
}
async function saveReport() {
  try {
    if (!lastQuery.value || !reportName.value.trim()) throw new Error('请先执行查询并填写报表名称')
    const response = await authorizedRequest('/api/reports', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...lastQuery.value, name: reportName.value, chartType: chartType.value }) })
    if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有报表管理或数据集权限' : '保存报表失败')
    message.value = '报表已保存，可在数据报表中查看'; reportName.value = ''; error.value = ''
  } catch (e) { error.value = e.message }
}
const columns = computed(() => rows.value.length ? Object.keys(rows.value[0]) : mode.value === 'preview' ? fields.value : [...groups.value, 'metric_0'])
onMounted(load)
</script>
<template>
<section>
  <div class="page-actions"><el-button @click="load">刷新数据集</el-button></div>
  <el-alert v-if="error && !queryOpen" :title="error" type="error" :closable="false" show-icon />
  <div class="toolbar"><el-input v-model="search" placeholder="搜索数据集" clearable aria-label="搜索数据集" /><span>已加载 {{ datasets.length }} 个数据集 · 本页分页</span></div>
  <div class="list-panel">
    <el-table :data="visible" empty-text="暂无已授权数据集"><el-table-column prop="name" label="数据集" min-width="180"/><el-table-column label="可用字段" min-width="260"><template #default="scope">{{ scope.row.fields.join('、') }}</template></el-table-column><el-table-column label="操作" width="130"><template #default="scope"><el-button text type="primary" @click="openDataset(scope.row)">查询分析</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" class="pagination" />
  </div>
  <el-dialog v-model="queryOpen" :title="`查询分析：${dataset?.name || ''}`" width="min(1100px, 96vw)" destroy-on-close @close="closeQuery">
    <el-alert v-if="message" :title="message" type="success" :closable="false" show-icon/><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <el-alert title="结果行数受平台上限限制。筛选和聚合只使用有明文权限的字段。" type="info" :closable="false" show-icon/>
    <div class="pane"><el-form label-position="top" @submit.prevent="execute"><div class="grid"><el-form-item label="当前数据集"><span>{{ dataset?.name }}</span></el-form-item><el-form-item label="查询方式"><el-radio-group v-model="mode"><el-radio-button value="preview">明细</el-radio-button><el-radio-button value="analysis">分组聚合</el-radio-button></el-radio-group></el-form-item></div><el-form-item v-if="mode === 'preview'" label="选择字段"><el-select v-model="fields" multiple><el-option v-for="f in dataset?.fields || []" :key="f" :label="f" :value="f"/></el-select></el-form-item><template v-else><div class="grid"><el-form-item label="分组字段"><el-select v-model="groups" multiple><el-option v-for="f in dataset?.fields || []" :key="f" :label="f" :value="f"/></el-select></el-form-item><el-form-item label="统计方式"><el-select v-model="metricOperation"><el-option v-for="m in ['COUNT','SUM','AVG','MIN','MAX']" :key="m" :label="m" :value="m"/></el-select></el-form-item><el-form-item label="统计字段（COUNT 可留空）"><el-select v-model="metricField" clearable><el-option v-for="f in dataset?.fields || []" :key="f" :label="f" :value="f"/></el-select></el-form-item><el-form-item label="排序字段"><el-select v-model="sortField" clearable><el-option label="统计结果" value="metric_0"/><el-option v-for="g in groups" :key="g" :label="g" :value="g"/></el-select></el-form-item><el-form-item v-if="sortField" label="排序方向"><el-select v-model="sortDirection"><el-option label="升序" value="ASC"/><el-option label="降序" value="DESC"/></el-select></el-form-item></div><div class="filters"><div class="filter-heading"><strong>筛选条件（全部满足）</strong><el-button text type="primary" :disabled="filters.length >= 20" @click="filters.push({ field:'', operation:'EQ', valueType:'string', value:'' })">添加条件</el-button></div><div v-for="(filter, index) in filters" :key="index" class="filter-row"><el-select v-model="filter.field" placeholder="字段" aria-label="筛选字段"><el-option v-for="f in dataset?.fields || []" :key="f" :label="f" :value="f"/></el-select><el-select :model-value="filter.operation" aria-label="筛选方式" @change="changeFilterOperation(filter, $event)"><el-option label="等于" value="EQ"/><el-option label="不等于" value="NE"/><el-option label="大于" value="GT"/><el-option label="大于等于" value="GTE"/><el-option label="小于" value="LT"/><el-option label="小于等于" value="LTE"/><el-option label="为空" value="IS_NULL"/><el-option label="不为空" value="IS_NOT_NULL"/></el-select><template v-if="!['IS_NULL','IS_NOT_NULL'].includes(filter.operation)"><el-select :model-value="filter.valueType || typeof filter.value" aria-label="筛选值类型" @change="changeFilterType(filter, $event)"><el-option label="文本" value="string"/><el-option label="数字" value="number"/><el-option label="布尔" value="boolean"/></el-select><el-input v-if="filter.valueType === 'number'" v-model="filter.value" inputmode="decimal" placeholder="安全精度数字" aria-label="筛选值"/><el-select v-else-if="typeof filter.value === 'boolean'" v-model="filter.value" aria-label="筛选值"><el-option label="是" :value="true"/><el-option label="否" :value="false"/></el-select><el-input v-else v-model="filter.value" placeholder="筛选值" aria-label="筛选值"/></template><el-button text type="danger" @click="filters.splice(index, 1)">删除</el-button></div></div></template><el-form-item label="最大返回行数（受平台上限约束）"><el-input-number v-model="rowLimit" :min="1" :max="1000"/></el-form-item><el-button type="primary" native-type="submit" :loading="busy">执行查询</el-button></el-form></div>
    <div v-if="lastQuery" class="pane"><div class="grid"><el-input v-model="reportName" placeholder="报表名称"/><el-select v-model="chartType"><el-option label="表格" value="TABLE"/><el-option label="柱状图" value="BAR"/><el-option label="折线图" value="LINE"/></el-select></div><p>保存的是最近一次成功查询的配置，查看报表时会重新检查数据权限。</p><el-button @click="saveReport">保存为报表</el-button></div>
    <el-table :data="rows" empty-text="暂无查询结果" max-height="360"><el-table-column v-for="c in columns" :key="c" :prop="c" :label="c" min-width="150"/></el-table>
  </el-dialog>
</section>
</template>
<style scoped>.toolbar{display:flex;align-items:center;gap:16px;margin:20px 0;color:#687e7b;font-size:13px}.toolbar .el-input{max-width:300px}.pagination{justify-content:flex-end;margin-top:18px}.pane{background:white;border:1px solid #e1eae8;padding:22px;margin:18px 0}.filters{margin:4px 0 20px}.filter-heading{display:flex;justify-content:space-between;align-items:center}.filter-row{display:flex;gap:10px;align-items:center;margin:8px 0}.filter-row > *{min-width:0}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}@media(max-width:700px){.grid{grid-template-columns:1fr}.filter-row{flex-wrap:wrap}.filter-row > *{flex:1 1 140px}}</style>
