export function safeNumber(raw) {
  if (typeof raw !== 'string' || !/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(raw)) return false
  const value = Number(raw)
  if (!Number.isFinite(value) || (Number.isInteger(value) && !Number.isSafeInteger(value))) return false
  const significand = raw.split(/[eE]/)[0].replace(/[-.]/g, '').replace(/^0+/, '').replace(/0+$/, '')
  if (value === 0 && significand.length > 0) return false
  return /^-?\d+$/.test(raw) ? Number.isSafeInteger(value) : significand.length <= 15
}
// JSON.parse rounds numbers before the editor can inspect them. Reject such snapshots.
export function verifyJsonNumbers(json) {
  let inString = false, escaped = false
  for (let i = 0; i < json.length; i++) {
    const char = json[i]
    if (inString) { if (escaped) escaped = false; else if (char === '\\') escaped = true; else if (char === '"') inString = false; continue }
    if (char === '"') { inString = true; continue }
    if (char === '-' || (char >= '0' && char <= '9')) {
      const token = json.slice(i).match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/)
      if (token) { if (!safeNumber(token[0])) throw new Error('任务参数或配置数字精度超出安全范围，不能安全编辑'); i += token[0].length - 1 }
    }
  }
}
