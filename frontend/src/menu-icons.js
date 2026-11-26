export const menuIconPaths = Object.freeze({
  grid: 'M4 4h7v7H4z M13 4h7v5h-7z M13 11h7v9h-7z M4 13h7v7H4z',
  database: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3Zm0 0v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6m-16 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6',
  assets: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Zm0 4h18',
  dataset: 'M4 5h16v14H4zM4 10h16M10 10v9',
  dataObject: 'M4 5h16v14H4zM4 9h16M9 9v10M14.5 9v10M4 14h16',
  dictionary: 'M5 4.5A2.5 2.5 0 0 1 7.5 2H19v17H7.5A2.5 2.5 0 0 0 5 21Zm0 16V4.5M9 7h6M9 11h7M9 15h5',
  masterData: 'M12 3 20 7v10l-8 4-8-4V7l8-4ZM4 7l8 4 8-4M12 11v10M8 5l8 4',
  workflow: 'M6 4v4a4 4 0 0 0 4 4h4a4 4 0 0 1 4 4v4m-12 0v-3m12-13v3',
  pulse: 'M3 12h4l3-7 4 14 3-7h4',
  settings: 'M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4m0-12.8L17 7M7 17l-1.4 1.4M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z',
  box: 'M12 3 20 7.5v9L12 21l-8-4.5v-9L12 3Zm-8 4.5 8 4.5 8-4.5m-8 4.5V21',
  search: 'M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13Zm5 11 4.5 4.5',
  chart: 'M4 20h16M7 16v-5m5 5V5m5 11v-8',
  system: 'M4 7h9m4 0h3M4 17h3m4 0h9m-7-2v4m-4-14v4m8-4v4',
  building: 'M3 21h18M5 21V7l7-4 7 4v14M9 10h.01M15 10h.01M9 14h.01M15 14h.01M10 21v-3h4v3',
  roles: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4 21a8 8 0 0 1 16 0m-3-12h4m-2-2v4',
  users: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4 21a8 8 0 0 1 16 0',
  menu: 'M4 6h16M4 12h16M4 18h16M8 6v.01M8 12v.01M8 18v.01',
  audit: 'M12 8v4l3 2m6-2a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  shield: 'M12 3 20 6v5c0 5-3.5 8.3-8 10-4.5-1.7-8-5-8-10V6l8-3Zm-3.5 8.5 2.2 2.2 4.8-4.8',
  driver: 'M9 3v4m6-4v4M7 7h10v3a5 5 0 0 1-10 0V7Zm5 8v6',
  processing: 'M4 5h16l-6 7v5l-4 2v-7L4 5Z',
})

const labels = {
  grid: '概览',
  database: '数据源',
  assets: '数据资产',
  dataset: '数据集',
  dataObject: '数据对象',
  dictionary: '数据字典',
  masterData: '主数据',
  workflow: '工作流',
  pulse: '实时同步',
  settings: '运维设置',
  box: '数据服务',
  search: '数据查询',
  chart: '数据报表',
  system: '系统管理',
  building: '部门',
  roles: '角色',
  users: '用户',
  menu: '菜单',
  audit: '审计',
  shield: '权限',
  driver: '驱动管理',
  processing: '数据处理',
}

export const menuIconOptions = Object.entries(menuIconPaths).map(([key, path]) => ({ key, path, label: labels[key] || key }))

export function menuIconPath(key) {
  return menuIconPaths[key] || menuIconPaths.menu
}
