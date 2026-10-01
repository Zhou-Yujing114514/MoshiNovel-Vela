/**
 * 摩柿小说 · TXT 阅读器纯逻辑模块（无 UI 依赖，可脱离页面单测心智验证）
 *
 * 设计依据：architecture.md §6 / §10.4（算法冻结）
 * 要点：
 *  - 大文件禁止整体 readText；一律 file.readArrayBuffer 分窗（窗口 4096B）读取；
 *  - UTF-8 解码复用 common/util.js 的 utf8Decode(u8, start, end)，
 *    它返回 {text, bytes}，并已在多字节截断处自动回退到合法码点边界；
 *  - 按 \n 切行，每页 linesPerPage 行；消费字节数 = 解码区间右端，
 *    保证下次 position 一定落在合法码点边界上（不截断汉字、不错位）；
 *  - 翻页即 store.setProgress(uri, off)，每页一次（节流）；
 *  - 上一页靠 pageOffsets 历史栈回退，不做反向窗口扫描。
 */
import file from '@system.file'
import store from './store.js'
import { utf8Decode } from './util.js'

// 单次读取的原始字节窗口（冻结值，勿调大——手环内存小）
var WINDOW_SIZE = 4096
// 默认每页显示行数（手环 9Pro 设计稿 480 宽 / 34px 字号下约可容纳 12 行）
var DEFAULT_LINES_PER_PAGE = 12

/**
 * 创建一个阅读器实例
 * @param {string} uri 书籍 internal:// 路径
 * @param {{linesPerPage?:number, fontSize?:number}} opts fontSize 仅记录，不参与纯逻辑
 * @returns {{init:Function,restore:Function,nextPage:Function,prevPage:Function,
 *           getOffset:Function,getTotal:Function,percent:Function}}
 */
function createReader(uri, opts) {
  opts = opts || {}
  var linesPerPage = opts.linesPerPage || DEFAULT_LINES_PER_PAGE

  var r = {
    uri: uri,                 // 书籍路径
    total: 0,                 // 文件总字节数（file.get 取得）
    off: 0,                   // 当前页起始字节偏移
    afterOff: 0,              // 当前页读完后，下一页应从哪个字节开始
    text: '',                 // 当前页已解码文本（缓存，供边界提示复用）
    pageOffsets: [],          // 向前翻页历史栈：记录"离开页"的起始偏移
    notFound: false,          // 文件不存在（file 错误码 301）标记
    _lines: linesPerPage,     // 每页行数
    _fontSize: opts.fontSize || 34,

    /**
     * 初始化：取文件总长度；失败容错为 0，文件不存在时置 notFound
     * @returns {Promise<{length:number, notFound:boolean, code?:number}>}
     */
    init: function () {
      var self = this
      return new Promise(function (resolve) {
        try {
          file.get({
            uri: self.uri,
            success: function (data) {
              self.total = (data && data.length) || 0
              resolve({ length: self.total, notFound: false })
            },
            fail: function (code) {
              self.total = 0
              self.notFound = (code === 301) // 301 = 文件不存在
              resolve({ length: 0, notFound: self.notFound, code: code })
            }
          })
        } catch (e) {
          self.total = 0
          resolve({ length: 0, notFound: false })
        }
      })
    },

    /**
     * 从指定字节偏移恢复阅读（由调用方从 store.getProgress 读出后传入）
     * @param {number} offset 字节偏移
     * @returns {Promise<{text:string, offset:number, end:boolean}>}
     */
    restore: function (offset) {
      var o = Number(offset) || 0
      if (o < 0) o = 0
      this.off = o
      this.afterOff = o          // 尚未读窗口，暂置为当前偏移
      this.pageOffsets = []      // 恢复点无前向历史，不能"上一页"
      return this._readPage()
    },

    /**
     * 下一页：从 afterOff 继续读窗口，切出前 linesPerPage 行
     * @returns {Promise<{text:string, offset:number, end:boolean,
     *                   atEnd?:boolean, fail?:boolean}>}
     *   end=true 表示本页已到文件尾；atEnd=true 表示本来就在最后一页（无新内容）
     */
    nextPage: function () {
      // 已经推进过文件尾：不再读盘，原样返回当前页并标记 atEnd
      if (this.afterOff >= this.total) {
        return Promise.resolve({
          text: this.text, offset: this.off, end: true, atEnd: true
        })
      }
      // 记录"离开页"的起点，供 prevPage 弹栈回退
      this.pageOffsets.push(this.off)
      this.off = this.afterOff
      return this._readPage()
    },

    /**
     * 上一页：弹栈回到上一个已记录的页起点；无历史则原样返回并标记 atStart
     * @returns {Promise<{text:string, offset:number, atStart?:boolean}>}
     */
    prevPage: function () {
      if (this.pageOffsets.length === 0) {
        return Promise.resolve({ text: this.text, offset: this.off, atStart: true })
      }
      this.off = this.pageOffsets.pop()
      return this._readPage()
    },

    /** 当前页起始字节偏移 */
    getOffset: function () { return this.off },
    /** 文件总字节数 */
    getTotal: function () { return this.total },

    /**
     * 阅读进度百分比 0-100（按当前页起始偏移 / 文件总长）
     * @returns {number}
     */
    percent: function () {
      if (!this.total || this.total <= 0) return 0
      var p = Math.floor(this.off / this.total * 100)
      if (p < 0) p = 0
      if (p > 100) p = 100
      return p
    },

    /**
     * 核心分页读取：从 this.off 起读一窗 4096B → 解码 → 切 N 行 → 推进偏移
     * 私有方法，外部不要直接调用。
     */
    _readPage: function () {
      var self = this
      return new Promise(function (resolve) {
        var pos = self.off
        file.readArrayBuffer({
          uri: self.uri,
          position: pos,
          length: WINDOW_SIZE,
          success: function (data) {
            // 不同固件版本 success 回调可能直接给 ArrayBuffer，也可能包一层
            var buf = data && (data.result || data.buffer) ? (data.result || data.buffer) : data
            var u8 = new Uint8Array(buf)
            var rawLen = u8.length

            // 读不到字节：视为文件尾
            if (rawLen <= 0) {
              self.afterOff = self.total
              self.text = ''
              store.setProgress(self.uri, pos) // 每页一次落盘
              resolve({ text: '', offset: pos, end: true })
              return
            }

            // 1) 整窗解码：utf8Decode 自动在窗口末尾的不完整多字节序列处截断，
            //    返回 {text, bytes}，bytes 即合法码点边界。
            var whole = utf8Decode(u8, 0, rawLen)
            var safeEnd = whole.bytes

            // 2) 在 [0, safeEnd) 内按原始字节找第 linesPerPage 个 '\n'(0x0A)。
            //    换行是单字节 ASCII，字节位置与字符位置一致，可直接在 u8 上扫。
            var nl = 0
            var cut = safeEnd
            for (var i = 0; i < safeEnd; i++) {
              if (u8[i] === 0x0A) {
                nl++
                if (nl >= self._lines) { cut = i + 1; break }
              }
            }

            // 3) 精确解码本页：cut 必然落在合法码点边界——
            //    要么是某个 '\n' 之后（单字节天然边界），要么是 safeEnd（util 已保证）。
            var page = utf8Decode(u8, 0, cut)
            var consumed = page.bytes
            if (consumed <= 0) consumed = 1 // 极端异常文件至少前进 1 字节，防死循环

            self.afterOff = pos + consumed
            self.text = page.text

            // 翻页/加载即写进度（节流：每页一次）
            store.setProgress(self.uri, pos)

            // 本页消费完已达文件尾 → end=true
            var eof = self.afterOff >= self.total
            resolve({ text: page.text, offset: pos, end: eof })
          },
          fail: function (code) {
            self.notFound = (code === 301)
            resolve({ text: self.text, offset: self.off, end: true, fail: true, code: code })
          }
        })
      })
    }
  }

  return r
}

export default {
  createReader: createReader
}
