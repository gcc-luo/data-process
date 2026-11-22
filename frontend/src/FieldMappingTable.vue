<script setup>
import { computed } from 'vue'
const props = defineProps({ modelValue: Array, sourceFields: Array, readonly: Boolean })
const emit = defineEmits(['update:modelValue', 'load-fields'])
const rows = computed(() => props.modelValue || [])
const TYPES = ['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATETIME', 'JSON']
const ERRORS = [{ v: 'FAIL', l: '报错' }, { v: 'NULL', l: '置空' }, { v: 'SKIP_ROW', l: '跳过该行' }]
function update(next) { emit('update:modelValue', next) }
function add() { update([...rows.value, { from: '', to: '', targetType: 'STRING', defaultValue: '', onError: 'FAIL' }]) }
function remove(i) { update(rows.value.filter((_, j) => j !== i)) }
function patch(i, key, value) { update(rows.value.map((r, j) => j === i ? { ...r, [key]: value } : r)) }
function move(i, dir) {
  const j = i + dir
  if (j < 0 || j >= rows.value.length) return
  const next = [...rows.value]
  ;[next[i], next[j]] = [next[j], next[i]]
  update(next)
}
</script>

<template>
  <div class="mapping-table">
    <div class="mapping-head">
      <span>源字段 → 目标字段</span>
      <div>
        <el-button text type="primary" size="small" :disabled="readonly" @click="emit('load-fields')">加载上游字段</el-button>
        <el-button text type="primary" size="small" :disabled="readonly" @click="add">+ 添加映射</el-button>
      </div>
    </div>
    <div v-if="!rows.length" class="mapping-empty">暂无映射，点击“添加映射”或“加载上游字段”。</div>
    <div v-for="(r, i) in rows" :key="i" class="mapping-row">
      <el-input v-model="r.from" size="small" placeholder="源字段" :disabled="readonly" list="orch-source-fields" @update:model-value="patch(i, 'from', $event)" />
      <span class="arrow">→</span>
      <el-input v-model="r.to" size="small" placeholder="目标字段" :disabled="readonly" @update:model-value="patch(i, 'to', $event)" />
      <el-select v-model="r.targetType" size="small" :disabled="readonly" @update:model-value="patch(i, 'targetType', $event)">
        <el-option v-for="t in TYPES" :key="t" :label="t" :value="t" />
      </el-select>
      <el-input v-model="r.defaultValue" size="small" placeholder="默认值" :disabled="readonly" @update:model-value="patch(i, 'defaultValue', $event)" />
      <el-select v-model="r.onError" size="small" :disabled="readonly" @update:model-value="patch(i, 'onError', $event)">
        <el-option v-for="e in ERRORS" :key="e.v" :label="e.l" :value="e.v" />
      </el-select>
      <div class="row-ops">
        <el-button text size="small" :disabled="readonly || i === 0" @click="move(i, -1)">↑</el-button>
        <el-button text size="small" :disabled="readonly || i === rows.length - 1" @click="move(i, 1)">↓</el-button>
        <el-button text type="danger" size="small" :disabled="readonly" @click="remove(i)">删除</el-button>
      </div>
    </div>
    <datalist id="orch-source-fields"><option v-for="f in sourceFields || []" :key="f" :value="f" /></datalist>
  </div>
</template>

<style scoped>
.mapping-table{display:flex;flex-direction:column;gap:6px}
.mapping-head{display:flex;justify-content:space-between;align-items:center;font-size:12px;color:#5a6b82}
.mapping-empty{color:#9aa4b2;font-size:12px;padding:8px 0}
.mapping-row{display:grid;grid-template-columns:1fr 16px 1fr;gap:4px;align-items:center;padding:6px;border:1px solid var(--el-border-color-lighter);border-radius:6px;background:#fafbfc}
.mapping-row .el-select{grid-column:span 1}
.arrow{color:#9aa4b2;text-align:center}
.row-ops{grid-column:1 / -1;display:flex;justify-content:flex-end;gap:2px}
</style>