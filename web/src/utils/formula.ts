/**
 * 极简 Excel 风格公式引擎（用于工资表单元格公式）：
 * - [字段名] 引用（由调用方 resolve 提供取值，可递归解析依赖链）
 * - 四则运算 + - * / 、括号、数字字面量、一元正负
 * - SUM(a, b, ...) 求和；ROUND(x, n) 四舍五入到 n 位小数（Excel 同名函数语义）
 * 非法表达式返回 NaN，不抛异常。
 */
const MAX_DEPTH = 20

/** Excel 的 ROUND 是「四舍五入」而非银行家舍入，这里对齐 Excel 行为。 */
function excelRound(v: number, digits: number): number {
  if (!Number.isFinite(v)) return NaN
  const d = Number.isFinite(digits) ? Math.trunc(digits) : 0
  const f = Math.pow(10, d)
  const scaled = v * f
  const rounded =
    scaled >= 0 ? Math.floor(scaled + 0.5) : Math.ceil(scaled - 0.5)
  return rounded / f
}
export function evalFormula(input: string, resolve: (label: string) => number, depth = 0): number {
  if (depth > MAX_DEPTH) return NaN
  const s = input
  let pos = 0

  const ws = () => {
    while (pos < s.length && /\s/.test(s[pos])) pos++
  }

  function parseExpr(): number {
    let v = parseTerm()
    for (;;) {
      ws()
      const c = s[pos]
      if (c === '+') {
        pos++
        v += parseTerm()
      } else if (c === '-') {
        pos++
        v -= parseTerm()
      } else {
        return v
      }
    }
  }

  function parseTerm(): number {
    let v = parseFactor()
    for (;;) {
      ws()
      const c = s[pos]
      if (c === '*') {
        pos++
        v *= parseFactor()
      } else if (c === '/') {
        pos++
        const d = parseFactor()
        v = d === 0 ? NaN : v / d
      } else {
        return v
      }
    }
  }

  function parseFactor(): number {
    ws()
    const c = s[pos]
    if (c === '(') {
      pos++
      const v = parseExpr()
      ws()
      if (s[pos] === ')') pos++
      return v
    }
    if (c === '-') {
      pos++
      return -parseFactor()
    }
    if (c === '+') {
      pos++
      return parseFactor()
    }
    if (s.slice(pos, pos + 4).toUpperCase() === 'SUM(') {
      pos += 4
      const args: number[] = []
      for (;;) {
        args.push(parseExpr())
        ws()
        if (s[pos] === ',') {
          pos++
          continue
        }
        if (s[pos] === ')') {
          pos++
          break
        }
        break
      }
      return args.reduce((a, b) => a + (Number.isFinite(b) ? b : 0), 0)
    }
    if (s.slice(pos, pos + 6).toUpperCase() === 'ROUND(') {
      pos += 6
      const value = parseExpr()
      ws()
      let digits = 0
      if (s[pos] === ',') {
        pos++
        digits = parseExpr()
        ws()
      }
      if (s[pos] === ')') pos++
      return excelRound(value, digits)
    }
    if (c === '[') {
      const end = s.indexOf(']', pos)
      if (end < 0) return NaN
      const label = s.slice(pos + 1, end).trim()
      pos = end + 1
      return resolve(label)
    }
    const m = /^(?:\d+(?:\.\d+)?|\.\d+)/.exec(s.slice(pos))
    if (m) {
      pos += m[0].length
      return Number(m[0])
    }
    pos++ // 跳过无法识别的字符，防止死循环
    return NaN
  }

  const v = parseExpr()
  ws()
  return pos >= s.length && Number.isFinite(v) ? v : Number.isFinite(v) ? v : NaN
}
