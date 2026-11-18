<script setup>
import { inject, onBeforeUnmount, onMounted, nextTick, ref } from 'vue'
import { authorizedRequest } from './auth.js'
import { chartColumns } from './report.js'
import { init, use } from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
use([BarChart, LineChart, GridComponent, TooltipComponent, CanvasRenderer])
const props = defineProps({ report: { type: Object, required: true } })
const can = inject('canPermission', () => false)
const rows = ref([]), error = ref(''), loading = ref(false), chart = ref(null)
let instance, observer
async function refresh() {
  loading.value = true; error.value = ''
  try {
    const response = await authorizedRequest(`/api/reports/${props.report.id}/run`, { method: 'POST' })
    if (!response.ok) throw new Error(response.status === 403 ? '数据权限已变化，无法查看此报表' : '报表查询失败')
    rows.value = await response.json(); await nextTick(); draw()
  } catch (e) { error.value = e.message; rows.value = []; instance?.clear() } finally { loading.value = false }
}
function draw() {
  if (props.report.chartType === 'TABLE' || !chart.value || !rows.value.length) return
  const { numeric, category } = chartColumns(rows.value)
  if (!numeric) { error.value = '图表需要至少一个数字字段，请调整查询或使用表格。'; return }
  instance ||= init(chart.value)
  instance.setOption({ tooltip: { trigger: 'axis' }, grid: { left: 48, right: 20, top: 24, bottom: 45 }, xAxis: { type: 'category', data: rows.value.map((r, i) => category ? String(r[category] ?? '') : String(i + 1)) }, yAxis: { type: 'value' }, series: [{ type: props.report.chartType === 'BAR' ? 'bar' : 'line', data: rows.value.map(r => r[numeric]), itemStyle: { color: '#3b8d7a' } }] }, true)
  if (!observer) { observer = new ResizeObserver(() => instance?.resize()); observer.observe(chart.value) }
}
onMounted(() => { if (can('report.report.execute') && can('datasource.query.read')) refresh() })
onBeforeUnmount(() => { observer?.disconnect(); instance?.dispose() })
</script>
<template><article class="report"><div class="heading"><h3>{{ report.name }}</h3><el-button v-if="can('report.report.execute') && can('datasource.query.read')" text :loading="loading" @click="refresh">刷新</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><el-empty v-if="!can('report.report.execute') || !can('datasource.query.read')" description="当前角色没有运行报表或读取数据的权限"/><div v-if="report.chartType !== 'TABLE' && rows.length" ref="chart" class="chart" role="img" :aria-label="`${report.name}图表，详细数据见下表`"></div><el-table :data="rows" empty-text="暂无数据"><el-table-column v-for="key in (rows.length ? Object.keys(rows[0]) : [])" :key="key" :prop="key" :label="key" min-width="120" /></el-table></article></template>
<style scoped>.report{background:white;border:1px solid #e0eae8;padding:18px;margin:14px 0}.heading{display:flex;align-items:center;justify-content:space-between}.heading h3{font-size:16px;margin:0}.chart{height:260px;width:100%;margin-top:15px}</style>
