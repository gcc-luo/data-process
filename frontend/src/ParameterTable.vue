<script setup>
import { computed } from 'vue'
const props = defineProps({ variables: Array, names: Array, readonly: Boolean })
const TYPES = ['STRING', 'NUMBER', 'BOOLEAN', 'DATE', 'DATETIME']
const SOURCES = [{ v: 'MANUAL', l: '手动/运行传入' }, { v: 'WATERMARK', l: '增量水位' }]

function variableFor(name) {
  let v = (props.variables || []).find(x => x.name === name)
  if (!v) {
    v = { name, type: 'STRING', valueSource: 'MANUAL', defaultValue: '', required: false, description: '' }
    props.variables.push(v)
  }
  return v
}
const rows = computed(() => (props.names || []).map(name => variableFor(name)))
</script>

<template>
  <div class="param-table">
    <div class="param-head"><span>参数绑定（来自 SQL 中的 :name 占位符）</span></div>
    <div v-if="!rows.length" class="param-empty">SQL 中没有命名占位符。使用 <code>:参数名</code> 声明绑定参数，禁止字符串拼接。</div>
    <table v-else class="param-grid">
      <thead><tr><th>参数名</th><th>类型</th><th>默认值</th><th>来源</th><th>说明</th></tr></thead>
      <tbody>
        <tr v-for="v in rows" :key="v.name">
          <td class="param-name">{{ v.name }}</td>
          <td><el-select v-model="v.type" size="small" :disabled="readonly"><el-option v-for="t in TYPES" :key="t" :label="t" :value="t" /></el-select></td>
          <td><el-input v-model="v.defaultValue" size="small" :disabled="readonly" placeholder="默认值" /></td>
          <td><el-select v-model="v.valueSource" size="small" :disabled="readonly"><el-option v-for="s in SOURCES" :key="s.v" :label="s.l" :value="s.v" /></el-select></td>
          <td><el-input v-model="v.description" size="small" :disabled="readonly" placeholder="说明" /></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.param-table{display:flex;flex-direction:column;gap:6px}
.param-head{font-size:12px;color:#5a6b82}
.param-empty{color:#9aa4b2;font-size:12px;line-height:1.6}
.param-empty code{background:#f0f2f5;padding:0 4px;border-radius:3px}
.param-grid{width:100%;border-collapse:collapse;font-size:12px}
.param-grid th{text-align:left;color:#8a94a6;font-weight:600;padding:4px;border-bottom:1px solid var(--el-border-color-lighter)}
.param-grid td{padding:4px;border-bottom:1px solid var(--el-border-color-lighter);vertical-align:middle}
.param-name{font-family:ui-monospace,Menlo,monospace;color:#33415c;white-space:nowrap}
</style>