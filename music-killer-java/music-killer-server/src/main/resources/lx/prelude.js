/*
 * 洛雪音乐（lx-music）v4 自定义音源脚本运行时 —— JS 侧契约实现。
 *
 * 参考 SincereXing/walkman-tv（Apache-2.0）的 user-api-preload.js 精简而来：
 * 去掉了 QuickJS 沙箱、原生调用队列与更新提示，只保留脚本真正需要的
 * lx.on / lx.send / lx.request / lx.utils.{crypto,buffer} 与定时器。
 *
 * 宿主（Java）提供：
 *   __host_http(payloadJson)  -> 同步 HTTP，返回 {statusCode,statusMessage,headers,body,bytes,error}
 *   __host_md5(str)           -> md5(encodeURIComponent(str)) 的 hex
 *   __host_b64_str(str)       -> utf8 字符串的 base64
 *   __host_b64_buf(arr)       -> 字节数组的 base64
 *   __host_unb64(str)         -> base64 的字节数组（JSON 数组字符串）
 *   __host_aes(dataB64,keyB64,ivB64,cipher) -> base64
 *   __host_rsa(dataB64,keyB64)              -> base64
 */
(function () {
  'use strict';

  if (typeof globalThis.URLSearchParams !== 'function') {
    const decode = (value) => decodeURIComponent(String(value).replace(/\+/g, ' '));
    const encode = (value) => encodeURIComponent(String(value)).replace(/%20/g, '+');
    globalThis.URLSearchParams = class URLSearchParams {
      constructor(init) {
        this._params = [];
        if (typeof init === 'string') {
          const query = init.startsWith('?') ? init.substring(1) : init;
          if (query) {
            for (const part of query.split('&')) {
              const i = part.indexOf('=');
              if (i < 0) this.append(decode(part), '');
              else this.append(decode(part.substring(0, i)), decode(part.substring(i + 1)));
            }
          }
        } else if (init && typeof init[Symbol.iterator] === 'function') {
          for (const entry of init) this.append(entry[0], entry[1]);
        } else if (init && typeof init === 'object') {
          for (const name of Object.keys(init)) this.append(name, init[name]);
        }
      }
      append(name, value) {
        this._params.push([String(name), String(value)]);
      }
      set(name, value) {
        name = String(name);
        this.delete(name);
        this.append(name, value);
      }
      get(name) {
        const hit = this._params.find((p) => p[0] === String(name));
        return hit ? hit[1] : null;
      }
      getAll(name) {
        return this._params.filter((p) => p[0] === String(name)).map((p) => p[1]);
      }
      has(name) {
        return this._params.some((p) => p[0] === String(name));
      }
      delete(name) {
        this._params = this._params.filter((p) => p[0] !== String(name));
      }
      forEach(cb, thisArg) {
        for (const [name, value] of this._params) cb.call(thisArg, value, name, this);
      }
      *entries() {
        for (const [name, value] of this._params) yield [name, value];
      }
      [Symbol.iterator]() {
        return this.entries();
      }
      toString() {
        return this._params.map((p) => encode(p[0]) + '=' + encode(p[1])).join('&');
      }
    };
  }

  const EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert' };
  const events = { request: null };
  const queue = new Map();
  let requestSeq = 0;
  let initInfo = null;
  let settled = null;

  const utils = {
    crypto: {
      md5(str) {
        if (typeof str !== 'string') throw new Error('param required a string');
        return __host_md5(str);
      },
      randomBytes(size) {
        const out = new Uint8Array(size);
        for (let i = 0; i < size; i++) out[i] = Math.floor(Math.random() * 256);
        return out;
      },
      aesEncrypt(buffer, mode, key, iv) {
        let cipher;
        if (mode === 'aes-128-cbc') cipher = 'CBC';
        else if (mode === 'aes-128-ecb') cipher = 'ECB';
        else throw new Error('unsupported aes mode: ' + mode);
        return new Uint8Array(JSON.parse(__host_aes(toB64(buffer), toB64(key), iv ? toB64(iv) : '', cipher)));
      },
      rsaEncrypt(buffer, key) {
        const pk = String(key).replace('-----BEGIN PUBLIC KEY-----', '').replace('-----END PUBLIC KEY-----', '');
        return new Uint8Array(JSON.parse(__host_rsa(toB64(buffer), pk)));
      },
    },
    buffer: {
      from(input, encoding) {
        if (typeof input === 'string') {
          if (encoding === 'base64') return new Uint8Array(JSON.parse(__host_unb64(input)));
          if (encoding === 'hex') return new Uint8Array(input.match(/.{1,2}/g).map((b) => parseInt(b, 16)));
          return new Uint8Array(JSON.parse(__host_unb64(__host_b64_str(input))));
        }
        if (Array.isArray(input) || ArrayBuffer.isView(input)) return new Uint8Array(input);
        throw new Error('Unsupported input type');
      },
      bufToString(buf, format) {
        const arr = Array.from(buf);
        if (format === 'hex') return arr.map((b) => b.toString(16).padStart(2, '0')).join('');
        if (format === 'base64') return __host_b64_buf(arr);
        if (format === 'binary') return buf;
        return __host_buf_str(JSON.stringify(arr));
      },
    },
  };

  function toB64(input) {
    if (typeof input === 'string') return __host_b64_str(input);
    if (Array.isArray(input) || ArrayBuffer.isView(input)) return __host_b64_buf(Array.from(input));
    throw new Error('bad binary input');
  }

  function httpRequest(url, opts, callback) {
    const options = opts || {};
    const payload = JSON.stringify({
      url: url,
      method: options.method || 'get',
      headers: options.headers || {},
      body: options.body === undefined || options.body === null ? null : options.body,
      form: options.form || null,
      formData: options.formData || null,
      timeout: typeof options.timeout === 'number' ? options.timeout : 0,
    });
    const raw = __host_http(payload);
    let resp;
    try {
      resp = JSON.parse(raw);
    } catch (e) {
      callback(new Error('bad host response'), null, null);
      return function () {};
    }
    if (resp.error) {
      callback(new Error(resp.error), null, null);
    } else {
      callback(null, resp, resp.body);
    }
    return function () {};
  }

  globalThis.lx = {
    EVENT_NAMES: EVENT_NAMES,
    version: '2.0.0',
    env: 'mobile',
    currentScriptInfo: {
      name: __host_script_name(),
      description: '',
      version: '',
      author: '',
      homepage: '',
      rawScript: '',
    },
    on(name, handler) {
      if (name === EVENT_NAMES.request || typeof name === 'undefined') {
        events.request = handler;
        return Promise.resolve();
      }
      return Promise.resolve();
    },
    send(name, data) {
      if (name === EVENT_NAMES.inited) {
        initInfo = data;
        return Promise.resolve();
      }
      return Promise.resolve();
    },
    request: httpRequest,
    utils: utils,
  };

  // 定时器：由宿主在等待脚本结果时驱动
  let timerSeq = 1;
  let timers = [];
  globalThis.setTimeout = function (fn, ms) {
    const id = timerSeq++;
    timers.push({ id: id, at: Date.now() + (ms || 0), fn: fn, args: Array.prototype.slice.call(arguments, 2) });
    return id;
  };
  globalThis.clearTimeout = function (id) {
    timers = timers.filter((t) => t.id !== id);
  };

  globalThis.__lx_pump = function () {
    const now = Date.now();
    const due = timers.filter((t) => t.at <= now);
    if (due.length) {
      timers = timers.filter((t) => t.at > now);
      for (const t of due) {
        try {
          t.fn.apply(null, t.args);
        } catch (e) {
          /* ignore */
        }
      }
    }
    return 0;
  };

  globalThis.__lx_sources = function () {
    return JSON.stringify((initInfo && initInfo.sources) || null);
  };

  globalThis.__lx_done = null;
  globalThis.__lx_invoke = function (source, action, infoJson) {
    if (!events.request) {
      globalThis.__lx_done = JSON.stringify({ error: 'script has no request handler' });
      return;
    }
    let info;
    try {
      info = JSON.parse(infoJson);
    } catch (e) {
      globalThis.__lx_done = JSON.stringify({ error: 'bad info json' });
      return;
    }
    try {
      Promise.resolve(events.request.call(globalThis.lx, { source: source, action: action, info: info })).then(
        (url) => {
          if (typeof url !== 'string' || !/^https?:/i.test(url)) {
            globalThis.__lx_done = JSON.stringify({ error: 'script returned invalid url' });
          } else {
            globalThis.__lx_done = JSON.stringify({ url: url });
          }
        },
        (err) => {
          globalThis.__lx_done = JSON.stringify({ error: String((err && err.message) || err) });
        }
      );
    } catch (e) {
      globalThis.__lx_done = JSON.stringify({ error: String((e && e.message) || e) });
    }
  };

  // 部分脚本用 lx.request 的 Promise 化封装（不需要 queue）
  globalThis.__lx_has_timers = function () {
    return timers.length;
  };
})();
