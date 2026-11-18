export function allowedDepartmentParents(departments, id) {
  const excluded = new Set(id == null ? [] : [id]); let changed
  do {
    changed = false
    for (const department of departments) if (excluded.has(department.parentId) && !excluded.has(department.id)) { excluded.add(department.id); changed = true }
  } while (changed)
  return departments.filter(department => !excluded.has(department.id))
}
export function departmentName(departments, id) {
  return id == null ? '无上级部门' : departments.find(department => department.id === id)?.name ?? `未知部门（${id}）`
}
