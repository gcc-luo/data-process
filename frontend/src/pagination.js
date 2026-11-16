import { computed, ref, watch } from 'vue'
/** Paginate the rows already returned by an existing list API. */
export function usePagination(rows, size = 10) {
  const page = ref(1), pageSize = ref(size)
  const total = computed(() => rows.value.length)
  const visible = computed(() => rows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
  watch([total, pageSize], () => { page.value = Math.min(Math.max(1, page.value), Math.max(1, Math.ceil(total.value / pageSize.value))) })
  return { page, pageSize, total, visible }
}
