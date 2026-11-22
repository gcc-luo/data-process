<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor'
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'

self.MonacoEnvironment = { getWorker: () => new editorWorker() }

const props = defineProps({ modelValue: { type: String, default: '' }, readonly: Boolean, language: { type: String, default: 'sql' } })
const emit = defineEmits(['update:modelValue', 'run'])
const container = ref(null)
const expanded = ref(false)
let editor = null
let applying = false

const KEYWORDS = ['SELECT', 'FROM', 'WHERE', 'GROUP BY', 'ORDER BY', 'HAVING', 'LIMIT', 'INSERT INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE FROM', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN', 'JOIN', 'ON', 'AND', 'OR', 'UNION ALL', 'UNION', 'WITH', 'AS', 'DISTINCT', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END']

function formatSql(text) {
  if (!text) return text
  let out = text
  for (const kw of KEYWORDS) {
    const re = new RegExp('\\b' + kw.replace(/ /g, '\\s+') + '\\b', 'gi')
    out = out.replace(re, kw)
  }
  out = out.replace(/\s+(FROM|WHERE|GROUP BY|ORDER BY|HAVING|LIMIT|LEFT JOIN|RIGHT JOIN|INNER JOIN|JOIN|UNION ALL|UNION|SET|VALUES)\b/g, '\n$1')
  out = out.replace(/\b(AND|OR)\b/g, '\n  $1')
  return out.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim()
}

function format() {
  if (!editor || props.readonly) return
  const formatted = formatSql(editor.getValue())
  editor.setValue(formatted)
  emit('update:modelValue', formatted)
}

onMounted(() => {
  editor = monaco.editor.create(container.value, {
    value: props.modelValue || '',
    language: props.language,
    theme: 'vs',
    automaticLayout: true,
    minimap: { enabled: false },
    lineNumbers: 'on',
    lineNumbersMinChars: 2,
    lineDecorationsWidth: 6,
    folding: false,
    glyphMargin: false,
    fontSize: 13,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
    scrollBeyondLastLine: false,
    wordWrap: 'on',
    readOnly: props.readonly,
    tabSize: 2,
    renderLineHighlight: 'line',
    padding: { top: 8, bottom: 8 },
    fixedOverflowWidgets: true
  })
  editor.onDidChangeModelContent(() => {
    if (applying) return
    emit('update:modelValue', editor.getValue())
  })
})

watch(() => props.modelValue, value => {
  if (!editor) return
  if (value !== editor.getValue()) {
    applying = true
    const position = editor.getPosition()
    editor.setValue(value || '')
    if (position) editor.setPosition(position)
    applying = false
  }
})
watch(() => props.readonly, value => editor?.updateOptions({ readOnly: value }))
watch(expanded, () => setTimeout(() => editor?.layout(), 60))

onBeforeUnmount(() => { editor?.getModel()?.dispose(); editor?.dispose() })
</script>

<template>
  <div class="sql-monaco" :class="{ expanded }">
    <div class="sql-toolbar">
      <span class="sql-lang">{{ language.toUpperCase() }}</span>
      <div class="sql-actions">
        <button type="button" class="sql-run" :disabled="readonly" title="执行查询并预览结果" @click="emit('run')">▶ 运行查询</button>
        <button type="button" :disabled="readonly" title="格式化 SQL" @click="format">&lt;&gt; 格式化</button>
        <button type="button" :title="expanded ? '退出全屏' : '全屏编辑'" @click="expanded = !expanded">⛶ {{ expanded ? '退出' : '全屏' }}</button>
      </div>
    </div>
    <div ref="container" class="sql-editor"></div>
  </div>
</template>

<style scoped>
.sql-monaco{width:100%;min-width:0;border:1px solid #e2e8f0;border-radius:8px;overflow:hidden;background:#fff}
.sql-toolbar{display:flex;align-items:center;justify-content:space-between;padding:5px 10px;background:#f8fafc;border-bottom:1px solid #eef2f6}
.sql-lang{font-size:11px;font-weight:600;color:#9aa4b2;letter-spacing:.5px}
.sql-actions{display:flex;gap:4px}
.sql-actions button{border:0;background:transparent;border-radius:5px;padding:3px 8px;font-size:12px;color:#6b7a90;cursor:pointer}
.sql-actions button:hover:not(:disabled){color:var(--el-color-primary);background:#eef2f6}
.sql-actions button:disabled{opacity:.5;cursor:not-allowed}
.sql-actions button.sql-run{color:var(--el-color-primary);font-weight:600}
.sql-actions button.sql-run:hover:not(:disabled){background:#ecf5ff}
.sql-editor{height:180px;width:100%}
.sql-monaco.expanded{position:fixed;inset:24px;z-index:3000;display:flex;flex-direction:column;box-shadow:0 12px 48px rgba(0,0,0,.25)}
.sql-monaco.expanded .sql-editor{flex:1;height:auto}
</style>