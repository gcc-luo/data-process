<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authorizedRequest } from './auth.js'
import MasterDataObjectTree from './MasterDataObjectTree.vue'

const can = inject('canPermission', () => false)
const tree = ref({ directories: [], objects: [] })
const selectedId = ref(0)
const detail = ref(null)
const pageData = ref({ rows: [], total: 0, page: 1, size: 20 })
const page = ref(1)
const size = ref(20)
const filters = ref([{ field: '', operator: 'CONTAINS', value: '' }])
const loadingTree = ref(false)
const loadingRows = ref(false)
const saving = ref(false)
const recordDialog = ref(false)
const recordMode = ref('create')
const recordValues = ref({})
const recordKey = ref({})
const importDialog = ref(false)
const importFile = ref(null)
const importing = ref(false)
const importResult = ref(null)
const historyDialog = ref(false)
const historyRows = ref([])
const historyPage = ref(1)
const historyTotal = ref(0)
const historyLoading = ref(false)
const selectedHistory = ref(null)
const selectedHistoryDetail = ref(null)
const historyDetailLoading = ref(false)
const writable = computed(() => detail.value?.object?.identity?.type === 'TABLE' && !detail.value?.schemaStale)
const hasExport = computed(() => ['TABLE', 'VIEW'].includes(detail.value?.object?.identity?.type))
const filterOperators = [
  { label: '包含', value: 'CONTAINS', text: true }, { label: '开头为', value: 'STARTS_WITH', text: true },
  { label: '等于', value: 'EQ' }, { label: '不等于', value: 'NE' }, { label: '大于', value: 'GT' },
  { label: '大于等于', value: 'GTE' }, { label: '小于', value: 'LT' }, { label: '小于等于', value: 'LTE' },
  { label: '为空', value: 'IS_NULL', noValue: true }, { label: '不为空', value: 'NOT_NULL', noValue: true },
]
const operationLabel = value => ({ CREATE: '新增', UPDATE: '修改', DELETE: '删除', IMPORT: '导入' }[value] || value || '—')
const statusLabel = value => ({ SUCCESS: '成功', FAILURE: '失败', PENDING: '处理中' }[value] || value || '—')
const messageOf = async response => {
  const payload = await response.json().catch(() => null)
  return payload?.error || payload?.message || payload?.detail || `请求失败（${response.status}）`
}
async function api(url, options) {
  const response = await authorizedRequest(url, options)
  if (!response.ok) throw new Error(await messageOf(response))
  return response
}
async function getJson(url, options) { return (await api(url, options)).json() }
function objectById(id) { return tree.value.objects?.find(object => object.id === id) }
function keyFor(row) {
  const key = {}
  for (const field of detail.value?.fields || []) if (field.primaryKey === 'YES') key[field.name] = row[field.name]
  return key
}
function keyReady(row) {
  const keys = (detail.value?.fields || []).filter(field => field.primaryKey === 'YES')
  return keys.length > 0 && keys.every(field => row[field.name] !== null && row[field.name] !== undefined)
}
function hasActionPermission(action) { return can(`datasource.master_data.${action}`) }

async function loadTree() {
  loadingTree.value = true
  try {
    tree.value = await getJson('/api/master-data/tree')
    if (!selectedId.value && tree.value.objects?.length) await selectObject(tree.value.objects[0].id)
  } catch (error) { ElMessage.error(error.message) }
  finally { loadingTree.value = false }
}
async function selectObject(id) {
  selectedId.value = id
  page.value = 1
  detail.value = null
  pageData.value = { rows: [], total: 0, page: 1, size: size.value }
  if (!id) return
  loadingRows.value = true
  try {
    detail.value = await getJson(`/api/master-data/objects/${id}`)
    await loadRows()
  } catch (error) { ElMessage.error(error.message) }
  finally { loadingRows.value = false }
}
function activeFilters() {
  return filters.value.filter(item => item.field && (['IS_NULL', 'NOT_NULL'].includes(item.operator) || String(item.value ?? '').trim() !== ''))
    .map(item => ({ field: item.field, operator: item.operator, value: item.value === '' ? null : item.value }))
}
async function loadRows() {
  if (!selectedId.value || !hasActionPermission('read')) return
  loadingRows.value = true
  try {
    pageData.value = await getJson(`/api/master-data/objects/${selectedId.value}/query`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ page: page.value, size: size.value, filters: activeFilters() }),
    })
  } catch (error) { ElMessage.error(error.message) }
  finally { loadingRows.value = false }
}
function query() { page.value = 1; void loadRows() }
function addFilter() { filters.value.push({ field: '', operator: 'EQ', value: '' }) }
function removeFilter(index) { filters.value.splice(index, 1); if (!filters.value.length) addFilter() }
function operatorsFor(filter) {
  const field = detail.value?.fields?.find(item => item.name === filter.field)
  const text = field && [1, 12, -1, -9, -10, -16, 2005, 2011].includes(field.jdbcType)
  return filterOperators.filter(operator => !operator.text || !filter.field || text)
}
function chooseField(filter, field) {
  filter.field = field
  if (!operatorsFor(filter).some(item => item.value === filter.operator)) filter.operator = 'EQ'
}
function newRecord() {
  recordMode.value = 'create'
  recordKey.value = {}
  recordValues.value = Object.fromEntries((detail.value?.fields || []).map(field => [field.name, null]))
  recordDialog.value = true
}
function editRecord(row) {
  recordMode.value = 'update'
  recordKey.value = keyFor(row)
  recordValues.value = Object.fromEntries((detail.value?.fields || []).map(field => [field.name, row[field.name] ?? null]))
  recordDialog.value = true
}
function textType(field) { return [1, 12, -1, -9, -10, -16, 2005, 2011].includes(field.jdbcType) }
function canSubmitField(field) {
  if (recordMode.value === 'update' && (field.primaryKey === 'YES' || field.autoIncrement === 'YES' || field.generated === 'YES')) return false
  if (recordMode.value === 'create' && (field.autoIncrement === 'YES' || field.generated === 'YES')) return false
  return true
}
function formValuesForSave() {
  const values = {}
  for (const field of detail.value?.fields || []) {
    if (!canSubmitField(field)) continue
    const value = recordValues.value[field.name]
    if (recordMode.value === 'create' && (value === null || value === undefined || value === '')) continue
    values[field.name] = value === '' && !textType(field) ? null : value
  }
  return values
}
async function saveRecord() {
  if (!selectedId.value) return
  saving.value = true
  try {
    const values = formValuesForSave()
    const update = recordMode.value === 'update'
    await api(`/api/master-data/objects/${selectedId.value}/records`, {
      method: update ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: update ? recordKey.value : null, values }),
    })
    ElMessage.success(update ? '主数据记录已修改' : '主数据记录已新增')
    recordDialog.value = false
    await loadRows()
  } catch (error) { ElMessage.error(error.message) }
  finally { saving.value = false }
}
async function deleteRecord(row) {
  if (!keyReady(row)) return
  try {
    await ElMessageBox.confirm('删除后无法恢复，确定删除这条主数据记录吗？', '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await api(`/api/master-data/objects/${selectedId.value}/records`, {
      method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(keyFor(row)),
    })
    ElMessage.success('主数据记录已删除')
    await loadRows()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error.message || '删除已取消') }
}
function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = filename; anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function downloadTemplate() {
  try { const response = await api(`/api/master-data/objects/${selectedId.value}/template`); saveBlob(await response.blob(), `主数据导入模板-${selectedId.value}.xlsx`) }
  catch (error) { ElMessage.error(error.message) }
}
async function exportRecords() {
  try {
    const params = new URLSearchParams()
    const applied = activeFilters()
    if (applied.length) params.set('filters', JSON.stringify(applied))
    const suffix = params.size ? `?${params}` : ''
    const response = await api(`/api/master-data/objects/${selectedId.value}/export${suffix}`)
    saveBlob(await response.blob(), `主数据-${objectById(selectedId.value)?.identity?.table || selectedId.value}.xlsx`)
  } catch (error) { ElMessage.error(error.message) }
}
function openImport() { importFile.value = null; importResult.value = null; importDialog.value = true }
function uploadRequest(options) {
  const form = new FormData()
  form.append('file', options.file)
  importing.value = true
  void api(`/api/master-data/objects/${selectedId.value}/import`, { method: 'POST', body: form })
    .then(response => response.json())
    .then(result => { importResult.value = result; options.onSuccess?.(result); ElMessage.success(`导入完成：成功 ${result.succeeded} 条，失败 ${result.failed} 条`); void loadRows() })
    .catch(error => { options.onError?.(error); ElMessage.error(error.message) })
    .finally(() => { importing.value = false })
}
function beforeImport(file) {
  const valid = file.name.toLowerCase().endsWith('.xlsx') && file.size <= 10 * 1024 * 1024
  if (!valid) ElMessage.error('请选择不超过 10 MB 的 .xlsx 文件')
  return valid
}
function handleImportChange(uploadFile) { importFile.value = uploadFile }
async function runImport(uploadRef) { if (importFile.value?.raw) uploadRef?.submit() }
async function openHistory() {
  historyDialog.value = true
  historyPage.value = 1
  selectedHistory.value = null
  selectedHistoryDetail.value = null
  await loadHistory()
}
async function loadHistory() {
  if (!selectedId.value) return
  historyLoading.value = true
  try {
    const result = await getJson(`/api/master-data/objects/${selectedId.value}/history?page=${historyPage.value}&size=20`)
    historyRows.value = result.rows || []
    historyTotal.value = result.total || 0
  } catch (error) { ElMessage.error(error.message) }
  finally { historyLoading.value = false }
}
async function showHistory(row) {
  selectedHistory.value = row
  selectedHistoryDetail.value = null
  historyDetailLoading.value = true
  try { selectedHistoryDetail.value = await getJson(`/api/master-data/history/${row.id}`) }
  catch (error) { ElMessage.error(error.message) }
  finally { historyDetailLoading.value = false }
}
function formatValue(value) {
  if (value === null || value === undefined) return 'NULL'
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}
function formatDate(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }
function historyFields() {
  const before = selectedHistoryDetail.value?.before || {}
  const after = selectedHistoryDetail.value?.after || {}
  const values = [...new Set([...Object.keys(before), ...Object.keys(after)])].map(name => ({ name, before: before[name], after: after[name], changed: JSON.stringify(before[name]) !== JSON.stringify(after[name]) }))
  return selectedHistoryDetail.value?.operation === 'UPDATE' ? values.filter(field => field.changed) : values
}
onMounted(() => { void loadTree() })
</script>

<template>
  <section class="master-workspace" aria-label="主数据">
    <MasterDataObjectTree :tree="tree" :selected-id="selectedId" @select="selectObject" />

    <main class="master-content">
      <template v-if="detail">
        <div class="master-object-heading">
          <div class="master-object-title">
            <h1>{{ detail.object.name || detail.object.identity.table }}</h1>
            <span class="physical-name">{{ detail.object.identity.table }}</span>
          </div>
          <div class="object-context">
            <span>{{ detail.object.sourceName }}</span><i></i><span>{{ detail.object.identity.catalog || detail.object.identity.schema || '默认数据库' }}</span><i></i>
            <span>{{ detail.object.identity.type === 'VIEW' ? '视图' : '数据表' }}</span><i></i><span>{{ tree.directories?.find(item => item.id === detail.object.directoryId)?.name || '未分类目录' }}</span>
          </div>
        </div>

        <el-alert v-if="detail.schemaStale" class="schema-alert" type="warning" :closable="false" show-icon title="当前物理字段与数据对象登记信息不一致。查询仍可使用，写入和导入已暂停；请先到数据对象刷新字段。" />
        <el-alert v-else-if="detail.writeDisabledReason" class="schema-alert" type="info" :closable="false" show-icon :title="detail.writeDisabledReason" />

        <div class="master-list-panel">
          <div class="master-toolbar toolbar list-query-toolbar">
            <div class="list-query-controls filters">
              <div v-for="(filter, index) in filters" :key="index" class="filter-row">
                <el-select :model-value="filter.field" placeholder="字段" aria-label="筛选字段" @change="chooseField(filter, $event)">
                  <el-option v-for="field in detail.fields" :key="field.name" :label="field.remarks ? `${field.remarks}（${field.name}）` : field.name" :value="field.name" />
                </el-select>
                <el-select v-model="filter.operator" placeholder="条件" aria-label="筛选方式">
                  <el-option v-for="operator in operatorsFor(filter)" :key="operator.value" :label="operator.label" :value="operator.value" />
                </el-select>
                <el-input v-if="!['IS_NULL','NOT_NULL'].includes(filter.operator)" v-model="filter.value" clearable placeholder="筛选内容" aria-label="筛选内容" @keyup.enter="query" />
                <el-button v-if="filters.length > 1" text type="danger" aria-label="删除筛选条件" @click="removeFilter(index)">移除</el-button>
              </div>
              <el-button class="add-filter" text @click="addFilter">＋ 条件</el-button>
              <el-button type="primary" @click="query">查询</el-button>
            </div>
            <div class="list-query-actions">
              <el-button v-if="hasActionPermission('create')" :disabled="!detail.canInsert" @click="newRecord">新增</el-button>
              <el-button v-if="hasActionPermission('import')" :disabled="!detail.canInsert" @click="openImport">导入</el-button>
              <el-button v-if="hasActionPermission('export')" :disabled="!hasExport" @click="exportRecords">导出</el-button>
              <el-button v-if="hasActionPermission('history')" @click="openHistory">版本记录</el-button>
              <el-button class="refresh-button" aria-label="刷新" title="刷新" :loading="loadingRows" @click="loadRows"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M16.1 7.1A6.5 6.5 0 0 0 4.6 5.8L3.2 7.4M3.2 7.4V3.9m0 3.5h3.5M3.9 12.9a6.5 6.5 0 0 0 11.5 1.3l1.4-1.6m0 0v3.5m0-3.5h-3.5" /></svg></el-button>
            </div>
          </div>

          <el-table v-loading="loadingRows" class="master-table" :data="pageData.rows" height="100%" :row-key="row => JSON.stringify(keyFor(row))" empty-text="暂无主数据记录">
            <el-table-column v-for="field in detail.fields" :key="field.name" :prop="field.name" :min-width="field.remarks?.length > 10 ? 180 : 150" show-overflow-tooltip>
              <template #header><el-tooltip :content="`${field.name} · ${field.type}${field.remarks ? ` · ${field.remarks}` : ''}`"><span>{{ field.remarks || field.name }}</span></el-tooltip></template>
              <template #default="scope"><span>{{ formatValue(scope.row[field.name]) }}</span></template>
            </el-table-column>
            <el-table-column v-if="hasActionPermission('update') || hasActionPermission('delete')" label="操作" width="138" fixed="right" align="center">
              <template #default="scope">
                <el-button v-if="hasActionPermission('update')" link type="primary" :disabled="!detail.canEditDelete || !keyReady(scope.row)" @click="editRecord(scope.row)">编辑</el-button>
                <el-button v-if="hasActionPermission('delete')" link type="danger" :disabled="!detail.canEditDelete || !keyReady(scope.row)" @click="deleteRecord(scope.row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="master-table-footer">
            <span>共 {{ pageData.total }} 条</span>
            <el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="[20, 50, 100]" :total="pageData.total" layout="sizes, prev, pager, next" @size-change="query" @current-change="loadRows" />
          </div>
        </div>
      </template>
      <div v-else class="master-empty" v-loading="loadingTree">
        <div class="empty-mark"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5.5h6l2 2h8v11H4z"/><path d="M4 9.5h16"/></svg></div>
        <h2>{{ tree.objects?.length ? '选择一个数据对象' : '还没有已登记的数据对象' }}</h2>
        <p>{{ tree.objects?.length ? '从左侧目录选择对象后查看和管理记录。' : '请先到数据对象完成对象登记。' }}</p>
      </div>
    </main>

    <el-dialog v-model="recordDialog" :title="recordMode === 'create' ? '新增主数据记录' : '编辑主数据记录'" width="min(760px, 94vw)" destroy-on-close>
      <div class="record-form-scroll">
        <el-form label-position="top" class="record-form">
          <el-form-item v-for="field in detail?.fields || []" :key="field.name" :label="field.remarks ? `${field.remarks}（${field.name}）` : field.name" :required="field.nullable !== 'YES' && field.defaultValue == null && field.autoIncrement !== 'YES' && field.generated !== 'YES'">
            <el-select v-if="[16, -7, -6].includes(field.jdbcType)" v-model="recordValues[field.name]" :disabled="!canSubmitField(field)" clearable placeholder="选择值">
              <el-option label="是" :value="true"/><el-option label="否" :value="false"/>
            </el-select>
            <el-input v-else v-model="recordValues[field.name]" :disabled="!canSubmitField(field)" :type="field.jdbcType === 91 ? 'date' : field.jdbcType === 92 ? 'time' : 'text'" :inputmode="[2,3,4,5,6,7,8,-5].includes(field.jdbcType) ? 'decimal' : undefined" :placeholder="field.defaultValue != null ? `留空使用数据库默认值：${field.defaultValue}` : `请输入${field.remarks || field.name}`" :maxlength="field.length || 4096" />
            <small v-if="!canSubmitField(field)" class="field-help">由数据库生成，保存时自动处理</small>
            <small v-else class="field-help">{{ field.type }}<template v-if="field.length"> · 最大 {{ field.length }}</template><template v-if="field.primaryKey === 'YES'"> · 主键</template></small>
          </el-form-item>
        </el-form>
      </div>
      <template #footer><el-button @click="recordDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveRecord">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="importDialog" title="导入主数据" width="min(620px, 94vw)" destroy-on-close>
      <div class="import-instructions">
        <p>按模板中的字段名填写，每行新增一条记录。公式、未知字段和超过 10 MB 的文件不支持。</p>
        <el-button @click="downloadTemplate">下载 Excel 模板</el-button>
      </div>
      <el-upload ref="uploadRef" drag :auto-upload="false" :limit="1" accept=".xlsx" :before-upload="beforeImport" :http-request="uploadRequest" :on-change="handleImportChange" :on-remove="() => importFile = null">
        <div class="upload-content"><strong>将 .xlsx 文件拖到这里</strong><span>或点击选择文件，单个文件不超过 10 MB</span></div>
      </el-upload>
      <div v-if="importResult" class="import-result">
        <el-alert :title="`成功 ${importResult.succeeded} 条，失败 ${importResult.failed} 条`" :type="importResult.failed ? 'warning' : 'success'" :closable="false" show-icon />
        <el-table v-if="importResult.errors?.length" :data="importResult.errors" max-height="220" size="small" style="margin-top:12px">
          <el-table-column prop="rowNumber" label="行" width="70"/><el-table-column prop="message" label="错误说明" show-overflow-tooltip/>
        </el-table>
      </div>
      <template #footer><el-button @click="importDialog = false">关闭</el-button><el-button type="primary" :disabled="!importFile?.raw" :loading="importing" @click="runImport($refs.uploadRef)">开始导入</el-button></template>
    </el-dialog>

    <el-dialog v-model="historyDialog" title="主数据版本记录" width="min(960px, 96vw)" destroy-on-close>
      <div class="history-layout">
        <div class="history-list">
          <el-table v-loading="historyLoading" :data="historyRows" height="390" highlight-current-row @row-click="showHistory">
            <el-table-column prop="occurredAt" label="时间" min-width="158"><template #default="scope">{{ formatDate(scope.row.occurredAt) }}</template></el-table-column>
            <el-table-column prop="operation" label="操作" width="80"><template #default="scope">{{ operationLabel(scope.row.operation) }}</template></el-table-column>
            <el-table-column prop="actorName" label="操作人" min-width="90"/>
            <el-table-column prop="status" label="结果" width="80"><template #default="scope"><el-tag size="small" :type="scope.row.status === 'SUCCESS' ? 'success' : scope.row.status === 'PENDING' ? 'warning' : 'danger'">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
            <el-table-column label="记录主键" min-width="150" show-overflow-tooltip><template #default="scope">{{ JSON.stringify(scope.row.key) }}</template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="historyPage" :page-size="20" :total="historyTotal" layout="prev, pager, next" @current-change="loadHistory" />
        </div>
        <aside class="history-detail" v-loading="historyDetailLoading">
          <template v-if="selectedHistoryDetail">
            <div class="history-detail-heading"><strong>{{ operationLabel(selectedHistoryDetail.operation) }}记录</strong><span>{{ formatDate(selectedHistoryDetail.occurredAt) }} · {{ selectedHistoryDetail.actorName || '未知操作人' }}</span></div>
            <el-alert v-if="selectedHistoryDetail.error" :title="selectedHistoryDetail.error" type="error" :closable="false" />
            <el-empty v-else-if="!historyFields().length" description="此版本没有字段快照" :image-size="48" />
            <el-table v-else :data="historyFields()" max-height="300" size="small">
              <el-table-column prop="name" label="字段" min-width="100"/>
              <el-table-column label="修改前" min-width="120"><template #default="scope">{{ formatValue(scope.row.before) }}</template></el-table-column>
              <el-table-column label="修改后" min-width="120"><template #default="scope">{{ formatValue(scope.row.after) }}</template></el-table-column>
            </el-table>
          </template>
          <el-empty v-else description="选择左侧版本查看字段快照" :image-size="48" />
        </aside>
      </div>
      <template #footer><el-button @click="historyDialog = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.master-workspace{height:100%;min-height:0;display:flex;gap:12px;overflow:hidden;color:var(--ui-text-body)}
.master-content{min-width:0;min-height:0;flex:1;display:flex;flex-direction:column;gap:10px;overflow:hidden}
.master-object-heading{flex:none;display:flex;align-items:center;justify-content:space-between;gap:20px;padding:12px 16px;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:4px}
.master-object-title{display:flex;align-items:baseline;gap:12px;min-width:0}.master-object-title h1{min-width:0;max-width:45vw;margin:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:16px;font-weight:650;color:var(--ui-text-primary)}
.physical-name{color:var(--ui-text-secondary);font-size:var(--ui-common-size);font-family:Consolas,"SFMono-Regular",monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.object-context{display:flex;align-items:center;gap:9px;flex:none;color:var(--ui-text-secondary);font-size:var(--ui-common-size)}.object-context i{width:3px;height:3px;border-radius:50%;background:#b7c3d0}
.schema-alert{flex:none}.master-list-panel{min-width:0;min-height:0;flex:1;display:flex;flex-direction:column;padding:12px;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:4px;box-shadow:var(--el-box-shadow-lighter);overflow:hidden}
.master-toolbar{flex:none;align-items:flex-start!important;gap:12px!important;margin-bottom:10px!important}.list-query-controls{align-items:flex-start!important;gap:7px!important}.list-query-actions{align-self:flex-start}.filters{display:flex;align-items:center;flex-wrap:wrap;gap:7px;min-width:0}.filter-row{display:flex;align-items:center;gap:7px;min-width:0}.filter-row :deep(.el-select){width:160px}.filter-row :deep(.el-input){width:190px}.add-filter{padding:0 4px!important;color:#4e79a4}
.master-table{min-height:0;flex:1;width:100%}.master-table-footer{flex:none;display:flex;justify-content:space-between;align-items:center;gap:12px;padding-top:10px;color:var(--ui-text-secondary);font-size:var(--ui-common-size)}
.master-table-footer :deep(.el-pagination){margin:0}.refresh-button svg{width:16px;height:16px;fill:none;stroke:currentColor;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}
.master-empty{flex:1;min-height:0;display:grid;align-content:center;justify-items:center;padding:30px;text-align:center;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:4px}.master-empty h2{margin:14px 0 4px;font-size:16px}.master-empty p{margin:0;color:var(--ui-text-secondary);font-size:var(--ui-common-size)}.empty-mark{width:44px;height:44px;display:grid;place-items:center;border-radius:50%;background:#eff4fa;color:#7790ab}.empty-mark svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:1.5;stroke-linejoin:round}
.record-form-scroll{max-height:min(60vh,620px);overflow:auto;padding:4px}.record-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));column-gap:16px}.record-form :deep(.el-form-item){min-width:0;margin-bottom:12px}.record-form :deep(.el-select),.record-form :deep(.el-input){width:100%}.field-help{display:block;margin-top:4px;color:var(--ui-text-muted);font-size:11px}.import-instructions{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:16px}.import-instructions p{margin:0;color:var(--ui-text-secondary);font-size:var(--ui-common-size);line-height:1.65}.upload-content{display:grid;gap:7px;padding:18px}.upload-content span{color:var(--ui-text-secondary);font-size:var(--ui-common-size)}.import-result{margin-top:14px}.history-layout{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(270px,.85fr);gap:14px;min-height:390px}.history-list{min-width:0}.history-list :deep(.el-pagination){justify-content:flex-end;margin-top:12px}.history-detail{min-width:0;padding:12px;border:1px solid var(--el-border-color-lighter);border-radius:4px;background:#fbfcfe}.history-detail-heading{display:grid;gap:6px;margin-bottom:12px}.history-detail-heading strong{font-size:14px;color:var(--ui-text-primary)}.history-detail-heading span{font-size:12px;color:var(--ui-text-secondary)}
@media(max-width:1000px){.master-object-heading{align-items:flex-start;flex-direction:column;gap:8px}.object-context{flex-wrap:wrap}.history-layout{grid-template-columns:1fr}.history-detail{min-height:180px}}
@media(max-width:760px){.master-workspace{height:auto;min-height:100%;flex-direction:column;overflow:visible}.master-content{min-height:540px;overflow:visible}.master-list-panel{min-height:470px}.master-toolbar{flex-direction:column}.list-query-controls{width:100%}.list-query-actions{width:100%;margin-left:auto!important;justify-content:flex-end!important}.filter-row{flex:1 1 100%;flex-wrap:wrap}.filter-row :deep(.el-select){flex:1 1 130px;width:auto}.filter-row :deep(.el-input){flex:1 1 160px;width:auto}.master-table{min-height:350px}.master-table-footer{align-items:flex-start;flex-direction:column}.master-table-footer :deep(.el-pagination){max-width:100%;overflow-x:auto}.record-form{grid-template-columns:1fr}.import-instructions{align-items:flex-start;flex-direction:column}}
</style>
