/** 编排界面首次使用步骤引导（driver.js 聚光灯导览）；「不再提示」按浏览器 localStorage 持久化。 */
import { driver } from 'driver.js'
import 'driver.js/dist/driver.css'

const KEY = 'datacraft.orch.guide.dismissed'
export const guideDismissed = () => { try { return localStorage.getItem(KEY) === '1' } catch { return false } }
export const dismissGuide = () => { try { localStorage.setItem(KEY, '1') } catch { /* 隐私模式等不可写时忽略 */ } }

export function startOrchGuide() {
  let dontShow = false
  const steps = [
    { popover: { title: '欢迎使用数据处理任务编排', description: '通过拖拽组件搭建数据处理流程：来源 → 处理 → 目的。下面带你快速认识各个区域。', side: 'over', align: 'center' } },
    { element: '.orch-editor .palette', popover: { title: '① 组件库', description: '按来源 / 处理 / 目的 / 控制分组，可搜索。拖拽或点击组件加入画布；标「待支持」的组件暂未开放。', side: 'right', align: 'start' } },
    { element: '.orch-editor .canvas-wrap', popover: { title: '② 编排画布', description: '拖动节点调整位置；从节点右侧连接点拖到下一节点左侧建立连线。工具栏支持缩放、适应、自动布局、小地图与撤销重做。', side: 'over', align: 'center' } },
    { element: '.orch-editor .canvas', popover: { title: '③ 节点配置', description: '点击任意节点，右侧弹出配置抽屉（基础配置 / 高级参数 / 重试策略 / 执行日志）。SQL 节点可点「运行查询」预览数据。', side: 'over', align: 'center' } },
    { element: '.orch-editor .header-actions', popover: { title: '④ 保存与发布', description: '保存草稿（Ctrl+S）→ 校验 → 发布生成不可变版本；「版本历史」可查看与回滚。「运行测试」不写目标，「运行」执行已发布版本。', side: 'bottom', align: 'center' } },
    { element: '.orch-editor .console-area', popover: { title: '⑤ 运行调试面板', description: '点击展开查看运行日志、节点状态与数据预览；发起运行或运行测试时会自动展开。', side: 'top', align: 'start' } }
  ]
  const instance = driver({
    showProgress: true,
    progressText: '{{current}} / {{total}}',
    nextBtnText: '下一步',
    prevBtnText: '上一步',
    doneBtnText: '开始使用',
    allowClose: true,
    overlayOpacity: 0.55,
    stagePadding: 6,
    stageRadius: 8,
    disableActiveInteraction: true,
    popoverClass: 'orch-guide-popover',
    steps,
    onPopoverRender: (popover) => {
      if (popover.footer.querySelector('.orch-guide-optout')) return
      const label = document.createElement('label')
      label.className = 'orch-guide-optout'
      const checkbox = document.createElement('input')
      checkbox.type = 'checkbox'
      checkbox.checked = dontShow
      checkbox.addEventListener('change', () => { dontShow = checkbox.checked })
      label.appendChild(checkbox)
      label.appendChild(document.createTextNode(' 不再提示'))
      popover.footer.insertBefore(label, popover.footer.firstChild)
    },
    onDestroyed: () => { if (dontShow) dismissGuide() }
  })
  instance.drive()
  return instance
}