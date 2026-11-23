<script setup>
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { iconPaths } from './orch-icons.js'
import { isConfigured, sqlPlaceholderNames } from './orch-graph.js'
import * as api from './orch-api.js'
import FieldMappingTable from './FieldMappingTable.vue'
import ParameterTable from './ParameterTable.vue'
const SqlMonaco = defineAsyncComponent(() => import('./SqlMonaco.vue'))

const props = defineProps({
  node: Object, component: Object, sources: Array, variables: Array,
  readonly: Boolean, nodeLogs: Array, nodeState: String, sourceFields: Array, targetFields: Array, tables: Array,
  tablesLoading: Boolean
})
const emit = defineEmits(['test-connection', 'new-datasource', 'load-tables', 'load-fields', 'close'])
const tab = ref('basic')
const CATEGORY_LABELS = { SOURCE: '来源组件', TRANSFORM: '处理组件', SINK: '目的组件', CONTROL: '控制组件', JOB: '作业组件' }
const configured = computed(() => isConfigured(props.component, props.node?.config))
const isWrite = computed(() => Boolean(props.component?.capabilities?.write))
const placeholderNames = computed(() => sqlPlaceholderNames(props.node?.config?.sql || ''))
// 防御：草稿节点可能缺 retry 对象，补全后再渲染，避免重试页签读取 undefined.enabled 崩溃
watch(() => props.node, n => { if (n && !n.retry) n.retry = { enabled: false, maxAttempts: 3, intervalSeconds: 30, backoff: 'FIXED', maxIntervalSeconds: 300, acknowledgeDuplicateRisk: false } }, { immediate: true })

// ---- ad-hoc query preview ----
const queryOpen = ref(false), queryLoading = ref(false), queryResult = ref(null), queryError = ref('')
const queryColumns = computed(() => {
  const cols = queryResult.value?.columns || []
  const rows = queryResult.value?.rows || []
  return cols.map((c, i) => {
    const numeric = rows.length > 0 && rows.every(r => r[i] == null || typeof r[i] === 'number')
    return { prop: 'c' + i, label: c, numeric, align: numeric ? 'right' : 'left' }
  })
})
const queryRows = computed(() => (queryResult.value?.rows || []).map(r => Object.fromEntries(queryColumns.value.map((c, i) => [c.prop, r[i]]))))
function exportQueryCsv() {
  if (!queryResult.value) return
  const esc = v => { const s = v == null ? '' : String(v); return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s }
  const csv = [queryResult.value.columns.map(esc).join(','), ...queryResult.value.rows.map(r => r.map(esc).join(','))].join('\n')
  const url = URL.createObjectURL(new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a'); a.href = url; a.download = 'query-result.csv'; a.click(); URL.revokeObjectURL(url)
}
async function runQuery(node) {
  const sourceId = node?.config?.sourceId
  const sql = node?.config?.sql
  if (!sourceId) { ElMessage.warning('请先选择数据源连接'); return }
  if (!sql || !sql.trim()) { ElMessage.warning('请先填写查询 SQL'); return }
  const values = { business_date: new Date().toLocaleDateString('sv-SE') }
  const types = { business_date: 'DATE' }
  for (const v of props.variables || []) { values[v.name] = v.defaultValue ?? ''; types[v.name] = v.type }
  queryOpen.value = true; queryLoading.value = true; queryError.value = ''; queryResult.value = null
  try { queryResult.value = await api.queryPreview({ sourceId, sql, values, types, limit: 100 }) }
  catch (e) { queryError.value = e.message } finally { queryLoading.value = false }
}

function whenMatch(f) {
  if (!f.when) return true
  const actual = props.node.config[f.when.field]
  const expected = f.when.equals
  return typeof expected === 'boolean' ? Boolean(actual) === expected : String(expected) === String(actual ?? '')
}
const basicFields = computed(() => (props.component?.fields || []).filter(f => f.tab === 'basic' && whenMatch(f)))
const advancedFields = computed(() => (props.component?.fields || []).filter(f => f.tab === 'advanced' && whenMatch(f)))
const filteredSources = computed(() => {
  const dbType = (props.component?.fields || []).find(f => f.type === 'source')?.dbType
  return (props.sources || []).filter(s => !dbType || s.type === dbType)
})
function columnsText(key) { return (props.node.config[key] || []).join(', ') }
function setColumns(key, text) { props.node.config[key] = String(text).split(',').map(s => s.trim()).filter(Boolean) }

// 清洗步骤
function addOperation() { (props.node.config.operations ||= []).push({ op: 'TRIM', fields: [], field: '', pattern: '', replacement: '' }) }
function removeOperation(i) { props.node.config.operations.splice(i, 1) }
// 分支规则
function addRule() { (props.node.config.rules ||= []).push({ key: '', label: '', expression: '' }) }
function removeRule(i) { props.node.config.rules.splice(i, 1) }
// 键值对
function addHeader(key) { (props.node.config[key] ||= []).push({ name: '', value: '' }) }
function removeHeader(key, i) { props.node.config[key].splice(i, 1) }

const retry = computed(() => props.node?.retry || {})
const stateLabel = { RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', SKIPPED: '跳过', PENDING: '待运行' }
</script>

<template>
  <aside class="inspector">
    <div v-if="!node" class="inspector-empty">
      <svg viewBox="0 0 24 24"><path d="M4 7c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3ZM4 7v10c0 1.7 3.6 3 8 3s8-1.3 8-3V7" /></svg>
      <p>选择一个节点查看配置</p>
      <span>点击画布中的节点，或从左侧组件库添加新节点。</span>
    </div>
    <template v-else>
      <div class="inspector-head">
        <span class="head-icon" :class="'ic-' + component.color">
          <svg viewBox="0 0 24 24"><path v-for="(d, i) in iconPaths(component.icon)" :key="i" :d="d" /></svg>
        </span>
        <div class="head-titles">
          <input class="head-name" v-model="node.name" maxlength="80" :disabled="readonly" placeholder="节点名称" />
          <span class="head-tag">{{ component.name }} · {{ CATEGORY_LABELS[component.category] }}</span>
        </div>
        <el-tag :type="configured ? 'success' : 'warning'" size="small" effect="light">{{ configured ? '已配置' : '待配置' }}</el-tag>
        <button type="button" class="inspector-close" title="关闭配置" @click="emit('close')">×</button>
      </div>
      <el-tabs v-model="tab" class="inspector-tabs">
        <el-tab-pane label="基础配置" name="basic">
          <el-form label-position="top" size="small" :disabled="readonly" class="inspector-form">
            <template v-for="f in basicFields" :key="f.key">
              <el-form-item :label="f.label" :required="f.required">
                <div v-if="f.type === 'source'" class="source-field">
                  <el-select v-model="node.config[f.key]" filterable clearable placeholder="选择数据源" class="grow">
                    <el-option v-for="s in filteredSources" :key="s.id" :label="`${s.name}（${s.type}）`" :value="s.id" />
                  </el-select>
                  <el-button text type="primary" :disabled="!node.config[f.key]" @click="emit('test-connection', node.config[f.key])">测试</el-button>
                  <el-button text type="primary" @click="emit('new-datasource')">新建</el-button>
                </div>
                <div v-else-if="f.type === 'table'" class="source-field">
                  <!-- 展开下拉即自动加载表列表，无需先点「浏览」 -->
                  <el-select v-model="node.config[f.key]" filterable allow-create default-first-option placeholder="表名（可 schema.table）" class="grow"
                             :loading="tablesLoading" @visible-change="v => v && node.config.sourceId && emit('load-tables', node.config.sourceId)">
                    <el-option v-for="t in tables || []" :key="t" :label="t" :value="t" />
                  </el-select>
                  <el-button text type="primary" :disabled="!node.config.sourceId" @click="emit('load-tables', node.config.sourceId)">浏览</el-button>
                </div>
                <SqlMonaco v-else-if="f.type === 'sql'" v-model="node.config[f.key]" :readonly="readonly" @run="runQuery(node)" />
                <el-input v-else-if="f.type === 'textarea'" v-model="node.config[f.key]" type="textarea" :rows="3" :placeholder="f.placeholder" />
                <el-input v-else-if="f.type === 'expr'" v-model="node.config[f.key]" type="textarea" :rows="2" :placeholder="f.placeholder" class="mono" />
                <el-input-number v-else-if="f.type === 'number'" v-model="node.config[f.key]" :min="f.min ?? 1" :max="f.max ?? 100000" controls-position="right" />
                <el-select v-else-if="f.type === 'select'" v-model="node.config[f.key]"><el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value" /></el-select>
                <el-switch v-else-if="f.type === 'switch'" v-model="node.config[f.key]" />
                <el-input v-else-if="f.type === 'columns'" :model-value="columnsText(f.key)" placeholder="逗号分隔" @update:model-value="setColumns(f.key, $event)" />
                <FieldMappingTable v-else-if="f.type === 'mappings'" v-model="node.config[f.key]" :source-fields="sourceFields" :target-fields="targetFields" :readonly="readonly" @load-fields="emit('load-fields')" />
                <ParameterTable v-else-if="f.type === 'params'" :variables="variables" :names="placeholderNames" :readonly="readonly" />
                <div v-else-if="f.type === 'operations'" class="list-editor">
                  <div v-for="(op, i) in node.config.operations || []" :key="i" class="list-row">
                    <el-select v-model="op.op" size="small" style="width:130px">
                      <el-option label="Trim 去空白" value="TRIM" /><el-option label="去空值行" value="DROP_NULL" />
                      <el-option label="去重" value="DEDUP" /><el-option label="正则替换" value="REGEX_REPLACE" />
                    </el-select>
                    <el-input v-if="op.op !== 'REGEX_REPLACE'" :model-value="(op.fields || []).join(',')" size="small" placeholder="作用字段（逗号分隔）" @update:model-value="op.fields = $event.split(',').map(s => s.trim()).filter(Boolean)" />
                    <template v-else>
                      <el-input v-model="op.field" size="small" placeholder="字段" />
                      <el-input v-model="op.pattern" size="small" placeholder="正则" />
                      <el-input v-model="op.replacement" size="small" placeholder="替换为" />
                    </template>
                    <el-button text type="danger" size="small" @click="removeOperation(i)">删除</el-button>
                  </div>
                  <el-button text type="primary" size="small" @click="addOperation">+ 添加步骤</el-button>
                </div>
                <div v-else-if="f.type === 'rules'" class="list-editor">
                  <div v-for="(r, i) in node.config.rules || []" :key="i" class="list-row">
                    <el-input v-model="r.key" size="small" placeholder="出口标识" style="width:90px" />
                    <el-input v-model="r.label" size="small" placeholder="名称" style="width:80px" />
                    <el-input v-model="r.expression" size="small" placeholder="条件表达式" class="grow mono" />
                    <el-button text type="danger" size="small" @click="removeRule(i)">删除</el-button>
                  </div>
                  <el-button text type="primary" size="small" @click="addRule">+ 添加分支</el-button>
                </div>
                <div v-else-if="f.type === 'headers'" class="list-editor">
                  <div v-for="(h, i) in node.config[f.key] || []" :key="i" class="list-row">
                    <el-input v-model="h.name" size="small" placeholder="名称" />
                    <el-input v-model="h.value" size="small" placeholder="值" />
                    <el-button text type="danger" size="small" @click="removeHeader(f.key, i)">删除</el-button>
                  </div>
                  <el-button text type="primary" size="small" @click="addHeader(f.key)">+ 添加</el-button>
                </div>
                <el-input v-else v-model="node.config[f.key]" :placeholder="f.placeholder" />
              </el-form-item>
            </template>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="高级参数" name="advanced">
          <el-form label-position="top" size="small" :disabled="readonly" class="inspector-form">
            <div class="form-section-cap">节点属性</div>
            <div class="form-row">
              <el-form-item label="启用状态"><el-switch v-model="node.enabled" /></el-form-item>
              <el-form-item label="超时时间（秒）"><el-input-number v-model="node.timeoutSeconds" :min="1" :max="86400" controls-position="right" /></el-form-item>
            </div>
            <el-form-item label="节点描述"><el-input v-model="node.description" type="textarea" :rows="2" maxlength="500" placeholder="选填，悬浮画布节点可见" /></el-form-item>
            <div v-if="advancedFields.length" class="form-section-cap component-cap">组件参数</div>
            <template v-for="f in advancedFields" :key="f.key">
              <el-form-item :label="f.label" :required="f.required">
                <el-input-number v-if="f.type === 'number'" v-model="node.config[f.key]" :min="f.min ?? 1" :max="f.max ?? 100000" controls-position="right" />
                <el-select v-else-if="f.type === 'select'" v-model="node.config[f.key]"><el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value" /></el-select>
                <el-switch v-else-if="f.type === 'switch'" v-model="node.config[f.key]" />
                <el-input v-else-if="f.type === 'textarea'" v-model="node.config[f.key]" type="textarea" :rows="3" :placeholder="f.placeholder" />
                <div v-else-if="f.type === 'headers'" class="list-editor">
                  <div v-for="(h, i) in node.config[f.key] || []" :key="i" class="list-row">
                    <el-input v-model="h.name" size="small" placeholder="名称" />
                    <el-input v-model="h.value" size="small" placeholder="值" />
                    <el-button text type="danger" size="small" @click="removeHeader(f.key, i)">删除</el-button>
                  </div>
                  <el-button text type="primary" size="small" @click="addHeader(f.key)">+ 添加</el-button>
                </div>
                <el-input v-else v-model="node.config[f.key]" :placeholder="f.placeholder" />
              </el-form-item>
            </template>
            <p v-if="!advancedFields.length" class="inspector-note">该组件没有高级参数。</p>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="重试策略" name="retry">
          <el-form label-position="top" size="small" :disabled="readonly" class="inspector-form">
            <el-form-item label="启用失败重试"><el-switch v-model="node.retry.enabled" /></el-form-item>
            <template v-if="node.retry.enabled">
              <div class="form-row">
                <el-form-item label="最多尝试次数（含首次）"><el-input-number v-model="node.retry.maxAttempts" :min="1" :max="5" controls-position="right" /></el-form-item>
                <el-form-item label="重试间隔（秒）"><el-input-number v-model="node.retry.intervalSeconds" :min="1" :max="3600" controls-position="right" /></el-form-item>
              </div>
              <div class="form-row">
                <el-form-item label="重试策略"><el-select v-model="node.retry.backoff"><el-option label="固定间隔" value="FIXED" /><el-option label="指数退避" value="EXPONENTIAL" /></el-select></el-form-item>
                <el-form-item label="最大间隔（秒）"><el-input-number v-model="node.retry.maxIntervalSeconds" :min="1" :max="86400" controls-position="right" /></el-form-item>
              </div>
              <el-alert v-if="isWrite" type="warning" :closable="false" show-icon class="retry-warn">
                写操作重试可能重放已提交数据（至少一次语义）。请确认目标具备幂等约束（如 UPSERT/幂等键）。
              </el-alert>
              <el-form-item v-if="isWrite">
                <el-checkbox v-model="node.retry.acknowledgeDuplicateRisk">我已确认幂等约束或允许重复风险</el-checkbox>
              </el-form-item>
            </template>
            <p v-else class="inspector-note">未启用重试，节点失败将终止当前分支。</p>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="执行日志" name="logs">
          <div class="node-logs">
            <div class="logs-head">
              <el-tag v-if="nodeState" size="small" :type="nodeState === 'SUCCEEDED' ? 'success' : nodeState === 'FAILED' ? 'danger' : 'info'">{{ stateLabel[nodeState] || nodeState }}</el-tag>
              <span v-else class="inspector-note">尚无运行记录</span>
            </div>
            <div v-if="!(nodeLogs || []).length" class="inspector-note">运行任务后，此处显示该节点的日志。</div>
            <div v-for="line in nodeLogs || []" :key="line.id" class="log-line" :class="(line.level || 'INFO').toLowerCase()">
              <span class="log-level">[{{ line.level }}]</span> {{ line.message }}
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-dialog v-model="queryOpen" width="min(920px,94vw)" append-to-body class="query-dialog">
      <template #header>
        <div class="query-header">
          <span class="query-title"><svg viewBox="0 0 24 24"><path d="M4 5h16v14H4zM4 10h16M9 10v9" /></svg>查询结果</span>
          <span v-if="queryResult" class="query-count">{{ queryResult.rowCount }} 行<template v-if="queryResult.truncated"> · 已截断</template></span>
        </div>
      </template>
      <div v-loading="queryLoading" class="query-body">
        <el-alert v-if="queryError" :title="queryError" type="error" :closable="false" show-icon style="margin-bottom:12px" />
        <template v-else-if="queryResult">
          <div class="query-toolbar">
            <span class="query-meta">共 {{ queryResult.rowCount }} 行<template v-if="queryResult.truncated">（最多显示 100 行）</template></span>
            <el-button size="small" class="ghost" :disabled="!queryResult.rowCount" @click="exportQueryCsv">导出 CSV</el-button>
          </div>
          <el-table :data="queryRows" size="small" max-height="440" stripe empty-text="无数据" class="query-table">
            <el-table-column type="index" label="#" width="56" align="center" />
            <el-table-column v-for="c in queryColumns" :key="c.prop" :prop="c.prop" :label="c.label" :align="c.align" :header-align="c.align" :class-name="c.numeric ? 'num-col' : ''" min-width="130" show-overflow-tooltip />
          </el-table>
        </template>
        <div v-else-if="!queryLoading" class="query-empty">执行查询后在此显示结果。</div>
      </div>
    </el-dialog>
  </aside>
</template>

<style scoped>
.inspector{height:100%;display:flex;flex-direction:column;min-height:0;background:#fff;padding:14px;overflow:hidden}
.inspector-close{flex:none;width:26px;height:26px;border:0;border-radius:6px;background:transparent;color:#8a94a6;font-size:20px;line-height:1;cursor:pointer}
.inspector-close:hover{background:#f0f2f5;color:#24344d}
.inspector-empty{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;color:#9aa4b2;text-align:center;padding:24px}
.inspector-empty svg{width:40px;height:40px;fill:none;stroke:#c3c9d1;stroke-width:1.4}
.inspector-empty p{font-size:14px;color:#5a6b82;margin:0}
.inspector-empty span{font-size:12px}
.inspector-head{display:flex;align-items:center;gap:8px;padding-bottom:10px;border-bottom:1px solid var(--el-border-color-lighter)}
.head-icon{width:30px;height:30px;border-radius:7px;display:flex;align-items:center;justify-content:center;background:#f5f7fa}
.head-icon svg{width:20px;height:20px;fill:none;stroke:#5a6b82;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}
.ic-blue svg{stroke:#2f7fd1}.ic-purple svg{stroke:#8250df}.ic-green svg{stroke:#2f9e57}.ic-orange svg{stroke:#d98a2b}
.head-icon.ic-blue{background:#eaf3fc}.head-icon.ic-purple{background:#f1ebfc}.head-icon.ic-green{background:#e9f7ee}.head-icon.ic-orange{background:#fdf3e0}
.head-titles{flex:1;min-width:0;display:flex;flex-direction:column}
.head-name{border:0;background:transparent;font-size:14px;font-weight:600;color:#24344d;padding:2px 4px;margin-left:-4px;border-radius:5px;width:100%;min-width:0}
.head-name:hover:not(:disabled){background:#f5f7fa}
.head-name:focus{outline:none;background:#f0f7ff;box-shadow:0 0 0 1px #b3d4fc}
.head-name:disabled{color:#24344d;cursor:default}
.head-tag{font-size:11px;color:#8a94a6}
.inspector-tabs{flex:1;min-height:0;display:flex;flex-direction:column}
.inspector-tabs :deep(.el-tabs__item){font-size:13px;height:38px;line-height:38px;font-weight:500}
.inspector-tabs :deep(.el-tabs__nav-wrap::after){height:1px}
.inspector-tabs :deep(.el-tabs__content){flex:1;min-height:0;overflow:auto}
.inspector-tabs :deep(.el-tabs__content .el-tab-pane){min-height:0;padding:0;border:0;border-radius:0;box-shadow:none;background:transparent}
.inspector-form{padding-right:6px}
.inspector-form :deep(.el-form-item){margin-bottom:18px}
.inspector-form :deep(.el-form-item__label){font-size:12px!important;color:#6b7a90;font-weight:500;padding-bottom:6px;line-height:1.4}
.inspector-form :deep(.el-form-item__label::before){margin-right:3px!important}
.inspector-form :deep(.el-input__inner){font-size:13px!important;color:#24344d}
.inspector-form :deep(.el-textarea__inner){font-size:13px!important;color:#24344d}
.inspector-form :deep(.el-input__wrapper),.inspector-form :deep(.el-textarea__inner){border-radius:6px}
.inspector-form :deep(.el-select__wrapper){border-radius:6px;min-height:32px}
.inspector-form :deep(.el-input-number){width:100%}
.form-section-cap{font-size:11px;font-weight:600;color:#9aa4b2;margin:0 0 12px;letter-spacing:.3px}
.form-section-cap.component-cap{margin-top:4px;padding-top:14px;border-top:1px solid #f0f2f5}
.form-row{display:flex;gap:12px}
.form-row .el-form-item{flex:1}
.source-field{display:flex;align-items:center;gap:6px;width:100%}
.source-field :deep(.el-button){flex:none;height:28px;padding:0 6px;font-size:12px!important;margin-left:0}
.source-field .grow{flex:1;min-width:0}
.grow{flex:1}
.mono :deep(textarea){font-family:ui-monospace,Menlo,monospace}
.list-editor{display:flex;flex-direction:column;gap:6px;width:100%}
.list-row{display:flex;gap:4px;align-items:center;flex-wrap:wrap}
.inspector-note{color:#9aa4b2;font-size:12px;line-height:1.6}
.retry-warn{margin-bottom:10px}
.node-logs{font-family:ui-monospace,Menlo,monospace;font-size:12px}
.logs-head{margin-bottom:8px}
.log-line{padding:2px 0;color:#5a6b82;word-break:break-all}
.log-line.error{color:#e05656}
.log-line.warn{color:#d98a2b}
.log-level{color:#9aa4b2}
.query-dialog :deep(.el-dialog__header){padding:16px 20px;margin-right:0;border-bottom:1px solid #f0f2f5}
.query-dialog :deep(.el-dialog__body){padding:16px 20px}
.query-dialog :deep(.el-dialog__headerbtn){top:16px;right:16px}
.query-header{display:flex;align-items:center;gap:10px}
.query-title{display:flex;align-items:center;gap:8px;font-size:15px;font-weight:600;color:#24344d}
.query-title svg{width:18px;height:18px;fill:none;stroke:#2f7fd1;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}
.query-count{font-size:12px;color:#5a6b82;background:#f0f2f5;border-radius:10px;padding:2px 10px}
.query-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}
.query-meta{font-size:12px;color:#8a94a6}
.query-toolbar :deep(.el-button){height:28px;padding:0 12px;font-size:12px!important;border-radius:6px;color:#5a6b82;border-color:#dce4ed}
.query-table{border-radius:8px;overflow:hidden}
.query-table :deep(th.el-table__cell){background:#f8fafc;color:#6b7a90;font-weight:600;font-size:12px;padding:8px 0}
.query-table :deep(.el-table__cell){padding:7px 0}
.query-table :deep(.el-table__cell .cell){font-size:13px;color:#33415c;padding:0 14px}
.query-table :deep(.el-table__cell.is-left .cell){text-align:left}
.query-table :deep(.el-table__cell.is-right .cell){text-align:right}
.query-table :deep(.el-table__cell.is-center .cell){text-align:center}
.query-table :deep(th.el-table__cell .cell){color:#6b7a90}
.query-table :deep(.num-col .cell){font-variant-numeric:tabular-nums;font-family:ui-monospace,Menlo,Consolas,monospace;color:#24344d}
.query-table :deep(.el-table__row:hover>td.el-table__cell){background:#f0f7ff}
.query-empty{color:#9aa4b2;font-size:13px;text-align:center;padding:36px}
@media(max-width:900px){.inspector{flex:none;width:100%}}</style>
