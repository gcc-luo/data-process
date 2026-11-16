<script setup>
import { onMounted, ref, computed } from 'vue'
import { authorizedRequest } from './auth.js'
import { usePagination } from './pagination.js'
import ReportView from './ReportView.vue'
import { reportEditor, reportUpdate, canSaveQuery, serializeEditedQuery } from './report-query-edit.js'
import { changeFilterType, changeFilterOperation } from './analysis-filters.js'
const reports = ref([]), dashboards = ref([]), error = ref(''), selectedReport = ref(null), selectedDashboard = ref(null), dashboardName = ref(''), reportIds = ref([]), editingDashboard = ref(null)
const dashboardDialog = ref(false), dashboardSaving = ref(false), reportViewOpen = ref(false), dashboardViewOpen = ref(false)
const { page: reportPage, visible: visibleReports } = usePagination(reports)
const { page: dashboardPage, visible: visibleDashboards } = usePagination(dashboards)
const reportDialog = ref(false), reportForm = ref({ id: null, name: '', chartType: 'TABLE' }), reportOriginal = ref(null)
const editor = ref(null), availableDatasets = ref([]), datasetsLoaded = ref(false), testedQuery = ref(null), queryTesting = ref(false), queryMessage = ref(''), dialogError = ref('')
const selectedDataset = computed(() => availableDatasets.value.find(d => d.id === reportOriginal.value?.datasetId))
const canEditQuery = computed(() => datasetsLoaded.value && !!selectedDataset.value)
const testedMatchesQuery = computed(() => !!testedQuery.value && !!editor.value && testedQuery.value === JSON.stringify(editor.value.query))
let editRevision = 0
const sortOptions = computed(() => [...(editor.value?.query.groups ?? []), ...(editor.value?.query.metrics ?? []).map((_, i) => `metric_${i}`)])
async function load() {
  try {
    const [a,b] = await Promise.all([fetch('/api/reports'),fetch('/api/dashboards')])
    if (!a.ok || !b.ok) throw new Error(a.status === 403 || b.status === 403 ? '当前账号没有报表管理权限' : '无法读取报表')
    reports.value = await a.json(); dashboards.value = await b.json()
    if (selectedReport.value) selectedReport.value = reports.value.find(r => r.id === selectedReport.value.id) ?? null
    if (selectedDashboard.value) selectedDashboard.value = dashboards.value.find(d => d.id === selectedDashboard.value.id) ?? null
    return true
  } catch (e) { error.value = e.message; return false }
}
async function saveDashboard() {
  if (dashboardSaving.value) return
  dashboardSaving.value = true; error.value = ''
  try {
    const response = await authorizedRequest(editingDashboard.value ? `/api/dashboards/${editingDashboard.value}` : '/api/dashboards', { method: editingDashboard.value ? 'PUT' : 'POST', headers: { 'Content-Type':'application/json' }, body: JSON.stringify({ name: dashboardName.value, reports: reportIds.value }) })
    if (!response.ok) throw new Error('保存失败，请选择至少一个自己的报表')
    resetDashboard(); dashboardDialog.value = false
    if (!await load()) error.value = '看板已保存，但列表刷新失败，请点击刷新查看。'
  } catch (e) { error.value = e.message }
  finally { dashboardSaving.value = false }
}
function editDashboard(dashboard) { editingDashboard.value = dashboard.id; dashboardName.value = dashboard.name; reportIds.value = [...dashboard.reports]; dashboardDialog.value = true }
function newDashboard() { resetDashboard(); error.value = ''; dashboardDialog.value = true }
function resetDashboard() { editingDashboard.value = null; dashboardName.value = ''; reportIds.value = [] }
async function deleteDashboard(dashboard) {
  try { const response = await authorizedRequest(`/api/dashboards/${dashboard.id}`, { method: 'DELETE' }); if (!response.ok) throw new Error('删除看板失败'); if (editingDashboard.value === dashboard.id) resetDashboard(); error.value = ''; await load() } catch (e) { error.value = e.message }
}
async function editReport(report) {
  try {
    const revision = ++editRevision
    editor.value = reportEditor(report); reportOriginal.value = report
    reportForm.value = { id: report.id, name: report.name, chartType: report.chartType }
    testedQuery.value = null; queryMessage.value = ''; dialogError.value = ''; availableDatasets.value = []; datasetsLoaded.value = false; queryTesting.value = false; reportDialog.value = true
    try {
      const response = await fetch('/api/accessible-datasets', { credentials:'same-origin' })
      const datasets = response.ok ? await response.json() : []
      if (revision === editRevision) availableDatasets.value = datasets
    } finally { if (revision === editRevision) datasetsLoaded.value = true }
  } catch (e) { error.value = e.message }
}
async function testQuery() {
  if (!canEditQuery.value || !reportOriginal.value || !editor.value) return
  queryTesting.value = true; dialogError.value = ''; queryMessage.value = ''; testedQuery.value = null
  const revision = editRevision, signature = JSON.stringify(editor.value.query)
  try {
    const requestBody = JSON.stringify(serializeEditedQuery(editor.value))
    const url = `/api/datasets/${reportOriginal.value.datasetId}/${reportOriginal.value.kind === 'PREVIEW' ? 'preview' : 'analysis'}`
    const response = await authorizedRequest(url, { method:'POST', headers:{'Content-Type':'application/json'}, body:requestBody })
    if (!response.ok) throw new Error(response.status === 403 ? '无权查询所选字段；筛选和聚合仅支持有明文权限的字段' : '测试查询失败，请检查字段、条件和数据库连接')
    const rows = await response.json()
    if (revision !== editRevision || !editor.value) return
    if (signature !== JSON.stringify(editor.value.query)) throw new Error('查询配置已更改，请重新测试')
    testedQuery.value = signature; queryMessage.value = `测试成功，返回 ${rows.length} 行；保存后查看时仍会重新检查权限。`
  } catch (e) { if (revision === editRevision) dialogError.value = e.message } finally { if (revision === editRevision) queryTesting.value = false }
}
async function saveReport() {
  try {
    const current = reportOriginal.value
    if (!editor.value || !canSaveQuery(editor.value, testedQuery.value)) throw new Error('查询配置已更改，请先成功测试当前配置')
    const body = reportUpdate(current, editor.value, reportForm.value.name, reportForm.value.chartType)
    const response = await authorizedRequest(`/api/reports/${current.id}`, { method:'PUT', headers:{ 'Content-Type':'application/json' }, body:JSON.stringify(body) })
    if (!response.ok) throw new Error(response.status === 403 ? '当前账号的数据权限已变化，无法编辑该报表' : '保存报表失败，请检查配置')
    reportDialog.value = false; error.value = ''; await load()
  } catch (e) { dialogError.value = e.message }
}
async function deleteReport(report) {
  try {
    const response = await authorizedRequest(`/api/reports/${report.id}`, { method:'DELETE' })
    if (!response.ok) throw new Error(response.status === 409 ? '报表已用于看板，请先从看板移除' : '删除报表失败')
    error.value = ''; await load()
  } catch (e) { error.value = e.message }
}
onMounted(load)
</script>
<template>
<section><div class="page-heading"><div><p class="kicker">数据分析</p><h1>数据报表</h1><p class="subheading">查看自己的报表并组合看板；每次查询都会检查当前权限。</p></div><el-button @click="load">刷新</el-button></div>
<el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
<el-tabs><el-tab-pane label="报表">
<div class="toolbar"><span>已加载 {{ reports.length }} 个报表</span></div><el-table :data="visibleReports" empty-text="请先在数据查询中保存报表"><el-table-column prop="name" label="名称"/><el-table-column prop="chartType" label="展示方式"/><el-table-column prop="kind" label="查询方式"/><el-table-column label="操作" min-width="230"><template #default="scope"><el-button text type="primary" @click="selectedReport = scope.row; reportViewOpen = true">查看</el-button><el-button text @click="editReport(scope.row)">编辑</el-button><el-popconfirm title="确认删除该报表？" @confirm="deleteReport(scope.row)"><template #reference><el-button text type="danger">删除</el-button></template></el-popconfirm></template></el-table-column></el-table>
<el-pagination v-model:current-page="reportPage" :page-size="10" :total="reports.length" layout="total, prev, pager, next"/><el-dialog v-model="reportViewOpen" :title="selectedReport?.name || '报表详情'" width="min(1000px, 95vw)" destroy-on-close><ReportView v-if="selectedReport && reportViewOpen" :key="`${selectedReport.id}-${selectedReport.chartType}-${selectedReport.configuration}`" :report="selectedReport"/></el-dialog>
</el-tab-pane><el-tab-pane label="看板">
<div class="toolbar"><span>已加载 {{ dashboards.length }} 个看板</span><el-button type="primary" @click="newDashboard">新建看板</el-button></div><el-dialog v-model="dashboardDialog" :title="editingDashboard ? '编辑看板' : '新建看板'" width="min(620px, 95vw)" :close-on-click-modal="!dashboardSaving" :close-on-press-escape="!dashboardSaving" :show-close="!dashboardSaving" @closed="resetDashboard"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><div class="row"><el-input v-model="dashboardName" placeholder="看板名称"/><el-select v-model="reportIds" multiple placeholder="选择报表"><el-option v-for="r in reports" :key="r.id" :label="r.name" :value="r.id"/></el-select><el-button type="primary" :loading="dashboardSaving" :disabled="dashboardSaving" @click="saveDashboard">{{ editingDashboard ? '保存修改' : '创建看板' }}</el-button><el-button :disabled="dashboardSaving" @click="dashboardDialog = false">取消</el-button></div></el-dialog>
<el-table :data="visibleDashboards"><el-table-column prop="name" label="看板"/><el-table-column label="操作" min-width="230"><template #default="scope"><el-button text type="primary" @click="selectedDashboard = scope.row; dashboardViewOpen = true">查看</el-button><el-button text @click="editDashboard(scope.row)">编辑</el-button><el-popconfirm title="确认删除该看板？报表将保留。" @confirm="deleteDashboard(scope.row)"><template #reference><el-button text type="danger">删除</el-button></template></el-popconfirm></template></el-table-column></el-table>
<el-pagination v-model:current-page="dashboardPage" :page-size="10" :total="dashboards.length" layout="total, prev, pager, next"/><el-dialog v-model="dashboardViewOpen" :title="selectedDashboard?.name || '看板详情'" width="min(1100px, 95vw)" destroy-on-close><template v-if="selectedDashboard && dashboardViewOpen" v-for="id in selectedDashboard.reports" :key="id"><ReportView v-if="reports.find(r => r.id === id)" :key="`${id}-${reports.find(r => r.id === id).chartType}-${reports.find(r => r.id === id).configuration}`" :report="reports.find(r => r.id === id)"/></template></el-dialog>
</el-tab-pane></el-tabs>
<el-dialog v-model="reportDialog" title="编辑报表" width="min(780px, 95vw)">
  <el-alert v-if="dialogError" :title="dialogError" type="error" :closable="false" show-icon/>
  <el-alert v-if="queryMessage && testedMatchesQuery" :title="queryMessage" type="success" :closable="false" show-icon/>
  <el-form v-if="editor" label-position="top" @submit.prevent="saveReport">
    <div class="grid"><el-form-item label="名称"><el-input v-model="reportForm.name"/></el-form-item><el-form-item label="展示方式"><el-select v-model="reportForm.chartType"><el-option label="表格" value="TABLE"/><el-option label="柱状图" value="BAR"/><el-option label="折线图" value="LINE"/></el-select></el-form-item></div>
    <h3>查询配置</h3>
    <p>数据集：{{ selectedDataset?.name || `编号 ${reportOriginal?.datasetId}` }}。修改查询后请先测试；测试和报表运行都会检查当前数据权限。</p>
    <el-alert v-if="datasetsLoaded && !canEditQuery" title="无法读取该数据集的授权字段；当前只能尝试修改报表名称和展示方式。" type="warning" :closable="false" show-icon/>
    <template v-if="canEditQuery">
      <el-form-item label="最大返回行数（受平台上限约束）"><el-input-number v-model="editor.query.limit" :min="1" :max="1000"/></el-form-item>
      <template v-if="reportOriginal.kind === 'PREVIEW'"><el-form-item label="展示字段"><el-select v-model="editor.query.fields" multiple><el-option v-for="field in selectedDataset.fields" :key="field" :label="field" :value="field"/></el-select></el-form-item></template>
      <template v-else>
        <el-form-item label="分组字段"><el-select v-model="editor.query.groups" multiple><el-option v-for="field in selectedDataset.fields" :key="field" :label="field" :value="field"/></el-select></el-form-item>
        <div class="configuration-group"><div class="group-heading"><strong>统计指标</strong><el-button text type="primary" @click="editor.query.metrics.push({ operation:'COUNT', field:null })">添加指标</el-button></div>
          <div v-for="(metric, index) in editor.query.metrics" :key="index" class="row"><el-select v-model="metric.operation" aria-label="统计方式"><el-option v-for="op in ['COUNT','SUM','AVG','MIN','MAX']" :key="op" :label="op" :value="op"/></el-select><el-select v-model="metric.field" clearable placeholder="字段，COUNT 可留空" aria-label="统计字段"><el-option v-for="field in selectedDataset.fields" :key="field" :label="field" :value="field"/></el-select><el-button text type="danger" :disabled="editor.query.metrics.length <= 1" @click="editor.query.metrics.splice(index, 1)">删除</el-button></div>
        </div>
        <div class="configuration-group"><div class="group-heading"><strong>筛选条件</strong><el-button text type="primary" @click="editor.query.filters.push({field:'',operation:'EQ',valueType:'string',value:''})">添加条件</el-button></div>
          <div v-for="(filter, index) in editor.query.filters" :key="index" class="row"><el-select v-model="filter.field" placeholder="字段" aria-label="筛选字段"><el-option v-for="field in selectedDataset.fields" :key="field" :label="field" :value="field"/></el-select><el-select v-model="filter.operation" aria-label="筛选方式" @change="changeFilterOperation(filter, $event)"><el-option v-for="op in ['EQ','NE','GT','GTE','LT','LTE','IS_NULL','IS_NOT_NULL']" :key="op" :label="op" :value="op"/></el-select><template v-if="!['IS_NULL','IS_NOT_NULL'].includes(filter.operation)"><el-select :model-value="filter.valueType || typeof filter.value" aria-label="筛选值类型" @change="changeFilterType(filter, $event)"><el-option label="文本" value="string"/><el-option label="数字" value="number"/><el-option label="布尔" value="boolean"/></el-select><el-input v-if="filter.valueType === 'number'" v-model="filter.value" inputmode="decimal" placeholder="安全精度数字" aria-label="筛选值"/><el-select v-else-if="typeof filter.value === 'boolean'" v-model="filter.value" aria-label="筛选值"><el-option label="是" :value="true"/><el-option label="否" :value="false"/></el-select><el-input v-else v-model="filter.value" placeholder="筛选值" aria-label="筛选值"/></template><el-button text type="danger" @click="editor.query.filters.splice(index, 1)">删除</el-button></div>
        </div>
        <div class="configuration-group"><div class="group-heading"><strong>排序</strong><el-button text type="primary" @click="editor.query.sort.push({field:'',direction:'ASC'})">添加排序</el-button></div>
          <div v-for="(order, index) in editor.query.sort" :key="index" class="row"><el-select v-model="order.field" placeholder="分组或指标" aria-label="排序字段"><el-option v-for="field in sortOptions" :key="field" :label="field" :value="field"/></el-select><el-select v-model="order.direction" aria-label="排序方向"><el-option label="升序" value="ASC"/><el-option label="降序" value="DESC"/></el-select><el-button text type="danger" @click="editor.query.sort.splice(index, 1)">删除</el-button></div>
        </div>
      </template>
      <el-button :loading="queryTesting" @click="testQuery">测试查询</el-button>
    </template>
    <div class="actions"><el-button type="primary" native-type="submit" :disabled="queryTesting">保存</el-button></div>
  </el-form>
</el-dialog>
</section>
</template>
<style scoped>.toolbar{display:flex;justify-content:space-between;align-items:center;margin:12px 0 18px;color:#637a75}.el-pagination{margin:18px 0;justify-content:flex-end}.pane{background:white;padding:20px;margin:16px 0}.row{display:flex;gap:12px;align-items:center;margin:8px 0}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.configuration-group{border-top:1px solid #e1eae8;padding:12px 0}.group-heading{display:flex;justify-content:space-between;align-items:center}.actions{margin-top:20px}@media(max-width:700px){.row{flex-wrap:wrap}.grid{grid-template-columns:1fr}}</style>
