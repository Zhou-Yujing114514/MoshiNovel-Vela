/**
 * 摩柿小说 · 本地存储层（@system.storage 封装）
 * 注意：Vela JS 非 node 环境，禁止引入 node 模块；所有方法均返回 Promise。
 * 存储键约定见 architecture.md §4。
 */
import storage from '@system.storage'

/**
 * 读取某个 key（不存在/失败均 resolve 为 null，不 reject）
 * @param {string} key
 * @returns {Promise<string|null>}
 */
function sGet(key) {
  return new Promise(function (resolve) {
    try {
      storage.get({
        key: key,
        success: function (data) { resolve(data == null ? null : data) },
        fail: function () { resolve(null) }
      })
    } catch (e) {
      resolve(null)
    }
  })
}

/**
 * 写入某个 key
 * @param {string} key
 * @param {string} value
 * @returns {Promise<void>}
 */
function sSet(key, value) {
  return new Promise(function (resolve) {
    try {
      storage.set({
        key: key,
        value: value,
        success: function () { resolve() },
        fail: function () { resolve() }
      })
    } catch (e) {
      resolve()
    }
  })
}

/**
 * 删除某个 key
 * @param {string} key
 * @returns {Promise<void>}
 */
function sDel(key) {
  return new Promise(function (resolve) {
    try {
      storage.delete({
        key: key,
        success: function () { resolve() },
        fail: function () { resolve() }
      })
    } catch (e) {
      resolve()
    }
  })
}

/** JSON 安全解析（失败返回 null） */
function parseJson(text) {
  if (text == null || text === '') return null
  try { return JSON.parse(text) } catch (e) { return null }
}

/* ------------------------------ 账号 ------------------------------ */

/**
 * 读取账号（{username, password}），无则 null
 * @param {string} svc 服务 key（本应用固定 'moshi'）
 * @returns {Promise<{username:string,password:string}|null>}
 */
function getAccount(svc) {
  return sGet('account.' + svc).then(parseJson)
}

/**
 * 写入账号；传 null 表示清除
 * @param {string} svc
 * @param {{username:string,password:string}|null} acc
 * @returns {Promise<void>}
 */
function setAccount(svc, acc) {
  if (acc == null) return sDel('account.' + svc)
  return sSet('account.' + svc, JSON.stringify(acc))
}

/* ------------------------------ 会话 ------------------------------ */

/**
 * 读取会话字符串，无则 ''
 * @param {string} svc
 * @returns {Promise<string>}
 */
function getSession(svc) {
  return sGet('session.' + svc).then(function (raw) {
    return raw == null ? '' : raw
  })
}

/**
 * 写入会话字符串
 * @param {string} svc
 * @param {string} s
 * @returns {Promise<void>}
 */
function setSession(svc, s) {
  return sSet('session.' + svc, s == null ? '' : '' + s)
}

/* ---------------------------- 书籍元数据 ---------------------------- */

/**
 * 读取某本书的元数据 {title,author,uri,size}，无则 null
 * @param {string} svc
 * @param {string|number} taskId
 * @returns {Promise<{title:string,author:string,uri:string,size:number}|null>}
 */
function getBookMeta(svc, taskId) {
  return sGet('book.' + svc + '.' + taskId).then(parseJson)
}

/**
 * 写入某本书的元数据
 * @param {string} svc
 * @param {string|number} taskId
 * @param {{title:string,author:string,uri:string,size:number}} meta
 * @returns {Promise<void>}
 */
function setBookMeta(svc, taskId, meta) {
  return sSet('book.' + svc + '.' + taskId, JSON.stringify(meta || {}))
}

/**
 * 删除某本书的元数据
 * @param {string} svc
 * @param {string|number} taskId
 * @returns {Promise<void>}
 */
function delBookMeta(svc, taskId) {
  return sDel('book.' + svc + '.' + taskId)
}

/* ----------------------------- 阅读进度 ----------------------------- */

/**
 * 读取某 uri 的阅读字节偏移（Number），无则 0
 * @param {string} uri
 * @returns {Promise<number>}
 */
function getProgress(uri) {
  return sGet('progress.' + uri).then(function (raw) {
    if (raw == null || raw === '') return 0
    var n = Number(raw)
    return isNaN(n) ? 0 : n
  })
}

/**
 * 写入某 uri 的阅读字节偏移
 * @param {string} uri
 * @param {number} offset
 * @returns {Promise<void>}
 */
function setProgress(uri, offset) {
  return sSet('progress.' + uri, String(offset || 0))
}

/* ---------------------------- 活动服务 ---------------------------- */

/**
 * 读取当前活动服务 key，无则 ''
 * @returns {Promise<string>}
 */
function getActiveService() {
  return sGet('active_service').then(function (raw) {
    return raw == null ? '' : raw
  })
}

/**
 * 写入当前活动服务 key
 * @param {string} svc
 * @returns {Promise<void>}
 */
function setActiveService(svc) {
  return sSet('active_service', svc == null ? '' : '' + svc)
}

export default {
  getAccount: getAccount,
  setAccount: setAccount,
  getSession: getSession,
  setSession: setSession,
  getBookMeta: getBookMeta,
  setBookMeta: setBookMeta,
  delBookMeta: delBookMeta,
  getProgress: getProgress,
  setProgress: setProgress,
  getActiveService: getActiveService,
  setActiveService: setActiveService
}
