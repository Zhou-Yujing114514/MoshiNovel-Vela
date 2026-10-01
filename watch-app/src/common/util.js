/**
 * 摩柿小说 · 常量与工具
 * 注意：Vela JS 非 node 环境，禁止引入 node 模块；本文件仅依赖 ES5/ES6 语法。
 */

// 本地书籍目录（Files 分区）
export const BOOKS_DIR = 'internal://files/books/'

// 桥接下载分片最大文本长度（与手机端协议保持一致，勿改）
export const CHUNK_MAX = 4000

// 协议版本
export const PROTOCOL_VERSION = 1

// 请求超时（毫秒）
export const REQUEST_TIMEOUT = 20000

// 数字补零
export function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

// 字节数格式化
export function formatSize(bytes) {
  if (!bytes || bytes <= 0) return '0B'
  if (bytes < 1024) return bytes + 'B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + 'KB'
  return (bytes / (1024 * 1024)).toFixed(1) + 'MB'
}

/**
 * UTF-8 安全解码（不依赖 TextDecoder）
 * 从 Uint8Array u8 的 [start, end) 区间解码：
 *  - 返回 { text, bytes }，bytes 为实际消费的字节数；
 *  - 若末尾字节构成不完整的多字节序列，则截断在最后一个完整码点，
 *    保证下次读取可从未消费的字节起始（翻页不错位、不出现乱码）。
 * @param {Uint8Array} u8
 * @param {number} start 起始字节下标（含）
 * @param {number} end 结束字节下标（不含）
 */
export function utf8Decode(u8, start, end) {
  var text = ''
  var i = start
  while (i < end) {
    var b0 = u8[i]
    var code, len
    if (b0 < 0x80) {
      // 1 字节 ASCII
      code = b0
      len = 1
    } else if ((b0 & 0xE0) === 0xC0) {
      code = b0 & 0x1F
      len = 2
    } else if ((b0 & 0xF0) === 0xE0) {
      code = b0 & 0x0F
      len = 3
    } else if ((b0 & 0xF8) === 0xF0) {
      code = b0 & 0x07
      len = 4
    } else {
      // 非法首字节：跳过
      i++
      continue
    }
    if (i + len > end) {
      // 不完整序列（窗口截断处）：停止，下页从当前字节继续
      break
    }
    var ok = true
    for (var k = 1; k < len; k++) {
      var bk = u8[i + k]
      if ((bk & 0xC0) !== 0x80) {
        // 非法续字节：跳过该首字节后重试
        i++
        ok = false
        break
      }
      code = (code << 6) | (bk & 0x3F)
    }
    if (!ok) continue
    if (code > 0xFFFF) {
      // 4 字节码点 → 代理对
      code -= 0x10000
      text += String.fromCharCode(0xD800 + (code >> 10), 0xDC00 + (code & 0x3FF))
    } else {
      text += String.fromCharCode(code)
    }
    i += len
  }
  return { text: text, bytes: i - start }
}
