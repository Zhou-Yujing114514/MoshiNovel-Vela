/**
 * 摩柿小说 · 网络桥接层（@system.interconnect 单例封装）
 * 背景：手环 9 Pro 无直连网络 API，统一经 BLE 与手机端桥接 App 通信。
 * 协议见 architecture.md §3；本文件只使用 vela-capability.md 确认存在的
 * interconnect / file 接口，非 node 环境。
 *
 * 对外能力：
 *  - getState() / onStateChange(cb)：连接状态机
 *  - request(action, payload, {timeoutMs, service})：请求-响应配对
 *  - onProgress(cb)：下载进度事件
 */
import interconnect from '@system.interconnect'
import file from '@system.file'
import { BOOKS_DIR, PROTOCOL_VERSION, REQUEST_TIMEOUT } from './util.js'

// 惰性获取单例连接：模块加载阶段不触碰 interconnect，
// 避免设备不支持该模块时整个应用启动即崩溃；首次实际使用才创建。
let connect = null
let connectTried = false

function getConnect() {
  if (connect) return connect
  if (connectTried) return null   // 已尝试过且失败，避免反复抛错
  connectTried = true
  try {
    connect = interconnect.instance()
  } catch (e) {
    console.warn('[bridge] interconnect.instance() 不可用：' + e)
    connect = null
  }
  if (connect) {
    connect.onopen = function (data) {
      setState('connected')
      openWaiters.slice().forEach(function (w) {
        try { w() } catch (e) {}
      })
      openWaiters.length = 0
    }
    connect.onmessage = function (evt) {
      var raw = evt && evt.data
      if (typeof raw !== 'string') {
        console.warn('bridge onmessage: 非字符串数据', raw)
        return
      }
      var msg
      try { msg = JSON.parse(raw) } catch (e) {
        console.warn('bridge onmessage: JSON 解析失败', raw)
        return
      }
      handleMessage(msg)
    }
    connect.onclose = function (data) {
      setState('disconnected')
      failAllPending({
        ok: false,
        code: (data && data.code) || 1006,
        message: '与手机桥接的连接已断开',
        payload: {}
      })
    }
    connect.onerror = function (data) {
      var code = (data && data.code) || 1000
      setState('disconnected')
      console.warn('bridge error', code, data && data.data)
      failAllPending({
        ok: false,
        code: code,
        message: '手机桥接连接异常，请检查蓝牙与手机端 App',
        payload: {}
      })
    }
    // 同步一次初始连接状态（FAQ 提示进入页面常先拿到断开）
    try {
      connect.getReadyState({
        success: function (data) {
          if (data && data.status === 1) {
            setState('connected')
            openWaiters.slice().forEach(function (w) { try { w() } catch (e) {} })
            openWaiters.length = 0
          } else {
            setState('disconnected')
          }
        },
        fail: function () { setState('disconnected') }
      })
    } catch (e) {}
  }
  return connect
}

// 未连接手机桥接时的统一中文提示（直接上屏）
const UNCONNECTED_MSG = '未连接手机桥接 App，请打开手机端桥接并保持蓝牙连接'

/* ------------------------------ 状态机 ------------------------------ */

// 'connecting' | 'connected' | 'disconnected'
let state = 'disconnected'
const stateListeners = []   // onStateChange 回调列表
const openWaiters = []       // 等待 onopen 的一次性 resolve 函数队列

function setState(s) {
  if (state === s) return
  state = s
  stateListeners.forEach(function (cb) {
    try { cb(s) } catch (e) { console.warn('onStateChange 回调异常', e) }
  })
}

/** 获取当前连接状态 */
function getState() { return state }

/** 注册连接状态变化回调 cb(state) */
function onStateChange(cb) { stateListeners.push(cb) }

/* --------------------------- 请求配对与下载 --------------------------- */

// 普通请求-响应配对表：id -> {resolve, reject, timer, action, bookKey}
const pendingMap = {}
// 下载请求按 bookKey 归类：bookKey -> {resolve, reject, uri, written, id}
const downloadPending = {}
// 下载进度回调列表
let progressCallbacks = []

// 请求自增序号
let seq = 0

/**
 * 6 位序号补零（与协议示例 req-000042 风格一致）
 * @param {number} n
 * @returns {string}
 */
function pad6(n) {
  var s = '' + n
  while (s.length < 6) s = '0' + s
  return s
}

/**
 * 派发下载进度事件
 * @param {{bookKey:string,percent:number,bytes:number,total:number}} ev
 */
function emitProgress(ev) {
  progressCallbacks.forEach(function (cb) {
    try { cb(ev) } catch (e) { console.warn('onProgress 回调异常', e) }
  })
}

/** 注册下载进度回调 cb({bookKey, percent, bytes, total}) */
function onProgress(cb) { progressCallbacks.push(cb) }

/** 连接断开/出错时，让所有在途请求与下载失败 */
function failAllPending(err) {
  Object.keys(pendingMap).forEach(function (id) {
    var p = pendingMap[id]
    if (p.timer) clearTimeout(p.timer)
    try { p.reject(err) } catch (e) {}
  })
  Object.keys(pendingMap).forEach(function (id) { delete pendingMap[id] })

  Object.keys(downloadPending).forEach(function (k) {
    var dp = downloadPending[k]
    try { dp.reject(err) } catch (e) {}
  })
  Object.keys(downloadPending).forEach(function (k) { delete downloadPending[k] })
}

/**
 * 等待连接就绪：未连接则主动诊断一次并等待 onopen，最多等 20s。
 * @returns {Promise<void>} resolve=已连接；reject={ok:false,message}
 */
function waitForOpen() {
  if (state === 'connected') return Promise.resolve()
  if (state !== 'connecting') setState('connecting')

  return new Promise(function (resolve, reject) {
    var timer = setTimeout(function () {
      cleanup()
      reject({ ok: false, code: -1, message: UNCONNECTED_MSG, payload: {} })
    }, 20000)

    var waiter = function () { cleanup(); resolve() }
    openWaiters.push(waiter)

    function cleanup() {
      clearTimeout(timer)
      var i = openWaiters.indexOf(waiter)
      if (i >= 0) openWaiters.splice(i, 1)
    }

    // 主动诊断一次，推动系统建立连接（诊断结果不打断等待）
    var c = getConnect()
    if (c) {
      try {
        c.diagnosis({
          timeout: 5000,
          success: function () {},
          fail: function () {}
        })
      } catch (e) {}
    }
  })
}

/* ---------------------------- 接收消息处理 ---------------------------- */

/**
 * 处理手机推送帧（kind 字段，见协议 §3.2）
 * @param {object} msg
 */
function handlePush(msg) {
  var p = msg.payload || {}

  if (msg.kind === 'download_progress') {
    // 进度帧：对外派发进度事件
    emitProgress({
      bookKey: p.bookKey,
      percent: p.percent,
      bytes: p.bytes,
      total: p.total
    })
    return
  }

  if (msg.kind === 'download_chunk') {
    var bookKey = p.bookKey
    var dp = downloadPending[bookKey]
    if (!dp) {
      // 与本地下载请求无关的分片，忽略
      console.warn('bridge: 收到未知 bookKey 的分片', bookKey)
      return
    }
    var text = p.chunk || ''
    // 首帧非 append 覆盖写，确保文件干净；后续 append 追加
    file.writeText({
      uri: dp.uri,
      text: text,
      append: dp.written,
      success: function () {
        dp.written = true
        if (p.eof) {
          // 该下载完成，resolve 对应请求
          delete downloadPending[bookKey]
          if (pendingMap[dp.id]) delete pendingMap[dp.id]
          dp.resolve({
            ok: true,
            code: 0,
            message: '',
            payload: { bookKey: bookKey, uri: dp.uri }
          })
        }
      },
      fail: function (data) {
        console.warn('bridge: 写分片失败', bookKey, data)
        delete downloadPending[bookKey]
        if (pendingMap[dp.id]) delete pendingMap[dp.id]
        dp.reject({
          ok: false,
          code: -3,
          message: '书籍写入失败，请重试',
          payload: {}
        })
      }
    })
    return
  }

  console.warn('bridge: 未知 kind 推送已忽略', msg.kind)
}

/**
 * 处理收到的整条消息（响应或推送），做 service/action/kind 校验
 * @param {object} msg
 */
function handleMessage(msg) {
  if (!msg || typeof msg !== 'object') return
  // 协议版本校验
  if (msg.v !== PROTOCOL_VERSION) {
    console.warn('bridge: 协议版本不匹配', msg.v)
    return
  }

  // 推送帧（带 kind）
  if (msg.kind) { handlePush(msg); return }

  // 请求响应（带 id + ok）
  if (msg.id && typeof msg.ok !== 'undefined') {
    var pending = pendingMap[msg.id]
    if (!pending) {
      console.warn('bridge: 收到无配对请求的响应', msg.id)
      return
    }
    if (pending.timer) { clearTimeout(pending.timer); pending.timer = null }

    if (pending.action === 'download') {
      // download：仅在手机拒绝(ok=false)时结束；ok=true 仅为受理确认，
      // 真正完成由分片流 eof 驱动
      delete pendingMap[msg.id]
      if (!msg.ok) {
        delete downloadPending[pending.bookKey]
        pending.reject({
          ok: false,
          code: msg.code || -1,
          message: msg.message || '下载被手机桥接拒绝',
          payload: {}
        })
      }
      return
    }

    // 普通请求：直接 resolve（服务端 ok=false 也 resolve，由上层提示）
    delete pendingMap[msg.id]
    pending.resolve({
      ok: !!msg.ok,
      code: msg.code || 0,
      message: msg.message || '',
      payload: msg.payload || {}
    })
    return
  }

  console.warn('bridge: 未知消息已忽略', msg)
}

/* ------------------------------ 请求入口 ------------------------------ */

/**
 * 发起一次桥接请求
 * @param {string} action  login | shelf | download | ping | http
 *        - 'http'：通用 fetch 等价通道（FetchBridge/自有桥接），payload 为
 *          {method, url, headers, body}；响应 payload 为 {status, headers, body, truncated?}
 *          （body 为文本，仅承载登录/书架等 JSON 小响应；大文件仍走 'download' 分片）
 * @param {object} payload 业务负载
 * @param {{timeoutMs?:number, service?:string}} [options]
 * @returns {Promise<{ok:boolean,code:number,message:string,payload:object}>}
 *          传输层失败（未连接/超时/发送失败）reject；服务端响应 resolve
 */
function request(action, payload, options) {
  options = options || {}
  var timeoutMs = options.timeoutMs || REQUEST_TIMEOUT
  var service = options.service || ''

  return waitForOpen().then(function () {
    return new Promise(function (resolve, reject) {
      seq += 1
      var id = 'req-' + pad6(seq)
      var isDownload = (action === 'download')
      var bookKey = (payload && payload.bookKey) || ''

      var envelope = {
        v: PROTOCOL_VERSION,
        id: id,
        service: service,
        action: action,
        payload: payload || {}
      }

      var pending = {
        resolve: resolve,
        reject: reject,
        timer: null,
        action: action,
        bookKey: bookKey
      }

      // download 不设普通超时：由分片流 eof 驱动结束
      if (!isDownload) {
        pending.timer = setTimeout(function () {
          delete pendingMap[id]
          reject({ ok: false, code: -2, message: '请求超时，请检查手机桥接是否正常', payload: {} })
        }, timeoutMs)
      }

      // 下载请求按 bookKey 归类，等待分片流
      if (isDownload) {
        downloadPending[bookKey] = {
          resolve: resolve,
          reject: reject,
          uri: BOOKS_DIR + bookKey + '.txt',
          written: false,
          id: id
        }
      }
      pendingMap[id] = pending

      // 发送：data 用 JSON 字符串，与 onmessage 的 String 对齐
      var c = getConnect()
      if (!c) {
        if (pending.timer) { clearTimeout(pending.timer); pending.timer = null }
        delete pendingMap[id]
        if (isDownload && bookKey) delete downloadPending[bookKey]
        reject({ ok: false, code: -1, message: UNCONNECTED_MSG, payload: {} })
        return
      }
      try {
        c.send({
          data: JSON.stringify(envelope),
          success: function () {},
          fail: function (data) {
            if (pending.timer) { clearTimeout(pending.timer); pending.timer = null }
            delete pendingMap[id]
            if (isDownload && bookKey) delete downloadPending[bookKey]
            reject({
              ok: false,
              code: (data && data.code) || -1,
              message: '发送失败，请检查蓝牙与手机桥接',
              payload: {}
            })
          }
        })
      } catch (e) {
        delete pendingMap[id]
        if (isDownload && bookKey) delete downloadPending[bookKey]
        reject({ ok: false, code: -1, message: '发送异常：' + e, payload: {} })
      }
    })
  })
}

export default {
  getState: getState,
  onStateChange: onStateChange,
  request: request,
  onProgress: onProgress
}
