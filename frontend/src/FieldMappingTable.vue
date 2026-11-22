<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
const props = defineProps({ modelValue: Array, sourceFields: Array, targetFields: Array, readonly: Boolean })
const emit = defineEmits(['update:modelValue', 'load-fields'])
const rows = computed(() => props.modelValue || [])
const expanded = ref({})
const TYPES = ['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATETIME', 'JSON']
const ERRORS = [{ v: 'FAIL', l: '报错' }, { v: 'NULL', l: '置空' }, { v: 'SKIP_ROW', l: '跳过该行' }]

const norm = arr => (arr || []).map(f => typeof f === 'string' ? { name: f, type: '' } : { name: f.name, type: f.type || '' })
const srcOptions = computed(() => norm(props.sourceFields))
const tgtOptions = computed(() => norm(props.targetFields))
const sourceTypeOf = name => srcOptions.value.find(f => f.name === name)?.type || ''
const targetTypeOf = name => tgtOptions.value.find(f => f.name === name)?.type || ''

function mapType(sqlType) {
  const t = (sqlType || '').toUpperCase()
  if (/BOOL|BIT/.test(t)) return 'BOOLEAN'
  if (/JSON/.test(t)) return 'JSON'
  if (/DATE|TIME|TIMESTAMP/.test(t)) return 'DATETIME'
  if (/DECIMAL|NUMERIC|FLOAT|DOUBLE|REAL|MONEY/.test(t)) return 'DECIMAL'
  if (/INT|SERIAL/.test(t)) return 'INTEGER'
  return 'STRING'
}

function update(next) { emit('update:modelValue', next) }
function add() { update([...rows.value, { from: '', to: '', targetType: 'STRING', defaultValue: '', onError: 'FAIL' }]) }
function remove(i) { update(rows.value.filter((_, j) => j !== i)) }
function patch(i, key, value) { update(rows.value.map((r, j) => j === i ? { ...r, [key]: value } : r)) }
function onFrom(i, value) { patch(i, 'from', value) }
function onTo(i, value) {
  const tt = targetTypeOf(value)
  update(rows.value.map((r, j) => j === i ? { ...r, to: value, targetType: tt ? mapType(tt) : r.targetType } : r))
}
function move(i, dir) {
  const j = i + dir
  if (j < 0 || j >= rows.value.length) return
  const next = [...rows.value]
  ;[next[i], next[j]] = [next[j], next[i]]
  update(next)
}
function autoMap() {
  const fields = srcOptions.value
  if (!fields.length) { emit('load-fields'); return }
  const targetNames = new Set(tgtOptions.value.map(f => f.name))
  const matches = fields.filter(f => targetNames.has(f.name))
  if (!matches.length) {
    ElMessage.warning('没有找到名称完全一致的目标字段，请先加载目标字段或手动配置映射')
    return
  }
  update(matches.map(f => ({ from: f.name, to: f.name, targetType: mapType(targetTypeOf(f.name)), defaultValue: '', onError: 'FAIL' })))
  if (matches.length < fields.length) ElMessage.info(`已映射 ${matches.length} 个同名字段，${fields.length - matches.length} 个未匹配字段已跳过`)
}
function toggle(i) { expanded.value = { ...expanded.value, [i]: !expanded.value[i] } }
const optionLabel = f => f.type ? `${f.name} · ${f.type}` : f.name
</script>

<template>
  <div class="fm">
    <div class="fm-toolbar">
      <span class="fm-title">字段映射<span class="fm-count">{{ rows.length }}</span></span>
      <div class="fm-actions">
        <el-button text type="primary" size="small" :disabled="readonly" @click="emit('load-fields')">加载字段</el-button>
        <el-button text type="primary" size="small" :disabled="readonly" title="按上游字段同名生成映射" @click="autoMap">自动映射</el-button>
        <el-button text type="primary" size="small" :disabled="readonly" @click="add">+ 添加</el-button>
      </div>
    </div>

    <div v-if="!rows.length" class="fm-empty">暂无映射。点「加载字段」从来源/目标读取字段，再「自动映射」同名生成，或「+ 添加」手动配置。</div>

    <div v-else class="fm-table">
      <div class="fm-head">
        <span class="fm-h-left">源字段<span class="fm-h-sub">{{ srcOptions.length ? `（${srcOptions.length}）` : '' }}</span></span>
        <span class="fm-h-right">目标字段<span class="fm-h-sub">{{ tgtOptions.length ? `（${tgtOptions.length}）` : '' }}</span></span>
      </div>
      <div v-for="(r, i) in rows" :key="i" class="fm-row-wrap" :class="{ open: expanded[i] }">
        <div class="fm-row">
          <div class="fm-cell-left">
            <svg class="fm-grip" viewBox="0 0 16 16" aria-hidden="true"><path d="M3 5h10M3 8h10M3 11h10" /></svg>
            <el-select :model-value="r.from" size="small" filterable allow-create default-first-option placeholder="源字段" :disabled="readonly" class="fm-field" @update:model-value="onFrom(i, $event)">
              <el-option v-for="f in srcOptions" :key="f.name" :label="optionLabel(f)" :value="f.name" />
            </el-select>
          </div>
          <svg class="fm-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12h15M13 6l6 6-6 6" /></svg>
          <div class="fm-cell-right">
            <el-select :model-value="r.to" size="small" filterable allow-create default-first-option placeholder="目标字段" :disabled="readonly" class="fm-field" @update:model-value="onTo(i, $event)">
              <el-option v-for="f in tgtOptions" :key="f.name" :label="optionLabel(f)" :value="f.name" />
            </el-select>
            <button type="button" class="fm-icon fm-expand" :class="{ active: expanded[i] }" :disabled="readonly" title="默认值 / 失败策略 / 排序" @click="toggle(i)">
              <svg viewBox="0 0 16 16"><path d="m4 6 4 4 4-4" /></svg>
            </button>
            <button type="button" class="fm-icon fm-del" :disabled="readonly" title="删除该映射" @click="remove(i)">
              <svg viewBox="0 0 16 16"><path d="M2.5 4.2h11M6 4.2V2.7h4v1.5M4.3 4.7l.6 8.1h6.2l.6-8.1M6.4 6.5v4.5M9.6 6.5v4.5" /></svg>
            </button>
          </div>
        </div>
        <div v-if="expanded[i]" class="fm-detail">
          <div class="fm-detail-field">
            <label>默认值</label>
            <el-input v-model="r.defaultValue" size="small" placeholder="源为空时填充" :disabled="readonly" @update:model-value="patch(i, 'defaultValue', $event)" />
          </div>
          <div class="fm-detail-field">
            <label>转换失败</label>
            <el-select v-model="r.onError" size="small" :disabled="readonly" @update:model-value="patch(i, 'onError', $event)">
              <el-option v-for="e in ERRORS" :key="e.v" :label="e.l" :value="e.v" />
            </el-select>
          </div>
          <div class="fm-order">
            <el-button text size="small" :disabled="readonly || i === 0" @click="move(i, -1)">↑ 上移</el-button>
            <el-button text size="small" :disabled="readonly || i === rows.length - 1" @click="move(i, 1)">↓ 下移</el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fm{display:flex;flex-direction:column;gap:8px;width:100%}
.fm-toolbar{display:flex;align-items:center;justify-content:space-between;gap:8px}
.fm-title{display:flex;align-items:center;gap:6px;font-size:12px;font-weight:600;color:#5a6b82}
.fm-count{font-size:11px;font-weight:500;color:#9aa4b2;background:#f0f2f5;border-radius:8px;padding:0 6px;line-height:16px}
.fm-actions{display:flex;gap:2px}
.fm-actions :deep(.el-button){padding:0 5px;font-size:12px!important;height:24px;margin-left:0}
.fm-empty{color:#9aa4b2;font-size:12px;line-height:1.7;padding:14px;border:1px dashed #e2e8f0;border-radius:8px;text-align:center}
.fm-table{border:1px solid #e8edf3;border-radius:8px;overflow:hidden}
.fm-head{display:flex;background:#f8fafc;border-bottom:1px solid #e8edf3;font-size:11px;font-weight:600;color:#8a94a6}
.fm-h-left{flex:1;padding:7px 10px 7px 30px}
.fm-h-right{flex:1;padding:7px 10px;border-left:1px solid #e8edf3}
.fm-h-sub{font-weight:400;color:#b6c2cf}
.fm-row-wrap{border-bottom:1px solid #f0f2f5}
.fm-row-wrap:last-child{border-bottom:0}
.fm-row-wrap.open{background:#fafcff}
.fm-row{display:flex;align-items:center;gap:6px;padding:6px 8px}
.fm-cell-left{flex:1;display:flex;align-items:center;gap:5px;min-width:0}
.fm-grip{width:13px;height:13px;flex:none;fill:none;stroke:#c3c9d1;stroke-width:1.5;stroke-linecap:round}
.fm-field{flex:1;min-width:0}
.fm-field :deep(.el-select__wrapper){min-height:26px;padding:0 8px;border-radius:5px}
.fm-field :deep(.el-select__selected-item){font-size:12px}
.fm-field :deep(.el-select__placeholder){font-size:12px}
.fm-arrow{width:16px;height:16px;flex:none;fill:none;stroke:#b6c2cf;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}
.fm-cell-right{flex:1;display:flex;align-items:center;gap:4px;min-width:0;border-left:1px solid #f0f2f5;padding-left:8px}
.fm-icon{flex:none;width:22px;height:22px;border:0;border-radius:5px;background:transparent;display:flex;align-items:center;justify-content:center;cursor:pointer;color:#9aa4b2;padding:0}
.fm-icon svg{width:14px;height:14px;fill:none;stroke:currentColor;stroke-width:1.4;stroke-linecap:round;stroke-linejoin:round}
.fm-icon:hover:not(:disabled){background:#eef2f6;color:#5a6b82}
.fm-icon:disabled{opacity:.4;cursor:not-allowed}
.fm-expand svg{transition:transform .15s}
.fm-expand.active svg{transform:rotate(180deg)}
.fm-expand.active{color:var(--el-color-primary)}
.fm-del:hover:not(:disabled){background:#fff2f0;color:#e05656}
.fm-detail{display:flex;align-items:flex-end;gap:8px;padding:0 8px 8px 30px;flex-wrap:wrap}
.fm-detail-field{display:flex;flex-direction:column;gap:3px;flex:1;min-width:120px}
.fm-detail-field label{font-size:11px;color:#8a94a6}
.fm-order{display:flex;gap:2px}
.fm-order :deep(.el-button){padding:0 4px;font-size:12px!important;height:24px;margin-left:0}
</style>
