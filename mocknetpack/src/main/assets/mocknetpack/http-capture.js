  const originalFetch = globalThis.fetch;
  const xhr = globalThis.XMLHttpRequest?.prototype;
  const encoder = new TextEncoder();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  const documentID = Array.from(crypto.getRandomValues(new Uint8Array(16)), v => v.toString(16).padStart(2, "0")).join("");
  const xhrRequests = new WeakMap();
  const requests = new Set();
  const omitted = { kind: "omitted" };
  const tooLarge = { kind: "tooLarge" };
  const unreadable = { kind: "unreadable" };
  let state;
  let departingState;
  let installed = false;
  let revision = 0;
  let nextID = 0;
  let readers = 0;
  let navigationReported = false;

  // 页面收回令牌后立即释放 SDK 自己的监听器、reader 和引用。
  function clearState() {
    state = undefined;
    for (const request of requests) retire(request);
  }

  // 每次只接受最新握手响应。请求不等待页面初始化或会话更新。
  function refresh(restored = false) {
    const current = ++revision;
    departingState = undefined;
    try {
      postBridge("hello", { restored }).then(reply => {
        if (current !== revision || !reply?.limit) return;
        installed = true;
        if (reply.token) {
          if (state?.token === reply.token) {
            state.mockKeys = new Set(reply.mockKeys || []);
            return;
          }
          clearState();
          state = { limit: reply.limit, token: reply.token, startedAt: reply.startedAt,
            mockKeys: new Set(reply.mockKeys || []) };
          resumeResourceCapture();
          reportNavigationWhenReady();
        } else {
          clearState();
          earlyResourceEvents.length = 0;
        }
      }).catch(() => {});
    } catch (_) {}
  }

  // Native 只通知对应文档重查状态，页面切换时取消旧文档采样。
  Object.defineProperty(globalThis, "__mockNetPackRefreshHTTP", {
    value: id => { if (id === documentID) refresh(); }
  });
  addEventListener("pagehide", () => {
    revision++;
    departingState = state;
    clearState();
  });
  addEventListener("pageshow", event => { if (event.persisted) refresh(true); });

  // 解析 HTTP 请求地址，保留页面提供的路径和查询参数。
  function requestURL(value) {
    if (typeof value !== "string" || value.length > 4096) return;
    const url = new URL(value, document.baseURI);
    if (!/^https?:$/.test(url.protocol)) return;
    url.hash = "";
    if (encoder.encode(url.href).length <= 4096) return url;
  }

  // 只读取普通数据属性，遇到业务 getter 时放弃该样本。
  function dataProperty(object, key) {
    const descriptor = object == null ? undefined : Object.getOwnPropertyDescriptor(object, key);
    if (descriptor && !Object.prototype.hasOwnProperty.call(descriptor, "value")) throw new TypeError();
    return descriptor?.value;
  }

  // 复用浏览器头部合并规则，读取页面直接提供的常见 HeadersInit。
  function headerPairs(input) {
    if (input instanceof Headers) return Headers.prototype.entries.call(input);
    if (input == null) return [];
    const pairs = [];
    if (Array.isArray(input)) {
      for (let i = 0; i < Math.min(input.length, 64); i++) {
        const pair = dataProperty(input, i);
        if (Array.isArray(pair)) pairs.push([dataProperty(pair, 0), dataProperty(pair, 1)]);
      }
    } else {
      for (const key of Object.keys(input).slice(0, 64)) pairs.push([key, dataProperty(input, key)]);
    }
    return pairs;
  }

  // 保留页面可见的请求头和响应头，限制单条记录的体积。
  function captureHeaders(pairs) {
    const headers = Object.create(null);
    let remaining = 8192;
    let count = 0;
    for (const [name, value] of pairs) {
      if (typeof name !== "string" || typeof value !== "string" || name.length + value.length > remaining) continue;
      const key = name.toLowerCase();
      const size = key.length + encoder.encode(value).length + 2;
      if (size > remaining || count >= 64) continue;
      headers[key] = headers[key] ? headers[key] + ", " + value.trim() : value.trim();
      remaining -= size;
      count++;
    }
    return headers;
  }

  // 保留文本原值。超限时以完整 UTF-8 字符截断。
  function textBody(text, limit) {
    if (!text) return;
    const bytes = encoder.encode(text.slice(0, limit));
    if (bytes.length <= limit && text.length <= limit) return { kind: "text", value: text };
    return truncatedText(bytes, limit);
  }

  // 预留截断标记，并清空解码器残留的半个字符。
  function truncatedText(bytes, limit) {
    const suffix = "\n[正文已截断]";
    const prefix = decoder.decode(bytes.subarray(0, limit - encoder.encode(suffix).length), { stream: true });
    try { decoder.decode(); } catch (_) {}
    return { kind: "text", value: prefix + suffix };
  }

  // 已结构化的预览不能当作浏览器发送的原始字节。
  function derivedBody(text, limit) {
    const sample = textBody(text, limit);
    return sample?.kind === "text" ? { ...sample, kind: "derived" } : sample;
  }

  // 保留表单键值；multipart 文件记录名称，原表单照常发送。
  function formBody(form, limit) {
    const items = new URLSearchParams();
    let remaining = limit;
    for (const [key, value] of form) {
      const text = typeof value === "string" ? value : value.name;
      if (typeof text !== "string") continue;
      remaining -= key.length + text.length + 2;
      if (remaining < 0) return truncatedText(encoder.encode(items.toString()), limit);
      items.append(key, text);
    }
    return derivedBody(items.toString(), limit);
  }

  // 沿用二进制正文的 base64 表示。
  function binaryBody(bytes, size) {
    let raw = "";
    for (let i = 0; i < bytes.length; i += 8192) raw += String.fromCharCode(...bytes.subarray(i, i + 8192));
    return { kind: "binary", value: btoa(raw), size };
  }

  // 采样请求参数副本，不读取 Request 已有的上传流。
  function requestBody(body, request) {
    const { limit } = request;
    if (body == null) return;
    if (typeof body === "string") return textBody(body, limit);
    if (body instanceof URLSearchParams) return textBody(body.toString(), limit);
    if (body instanceof FormData) return formBody(body, limit);
    if (body instanceof Blob) return blobBody(body, limit);
    if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) {
      const bytes = body instanceof ArrayBuffer ? new Uint8Array(body)
        : new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
      return bytesBody(bytes.subarray(0, limit), bytes.length, limit);
    }
    return omitted;
  }

  // 与 Native 样本一致，可解码的 UTF-8 按文本保存，其余按 base64 保存。
  function bytesBody(bytes, size, limit) {
    if (!bytes.length) return;
    try {
      return size <= limit ? { kind: "text", value: decoder.decode(bytes) } : truncatedText(bytes, limit);
    } catch (_) {
      return binaryBody(bytes, size);
    }
  }

  // Blob 只读取切片，并把页面返回和样本超时分开。
  function blobBody(blob, limit) {
    if (!blob.size) return;
    const read = blob.slice(0, limit).arrayBuffer()
      .then(buffer => bytesBody(new Uint8Array(buffer), blob.size, limit), () => unreadable);
    return boundedBodyPromise(read);
  }

  // 控制没有可取消 reader 的浏览器副本读取等待时间。
  function boundedBodyPromise(read) {
    return new Promise(resolve => {
      const timer = setTimeout(() => resolve({ kind: "timedOut" }), 2000);
      read.then(value => { clearTimeout(timer); resolve(value); }, () => { clearTimeout(timer); resolve(unreadable); });
    });
  }

  // 只异步读取有限字节。实时事件流继续由页面消费。
  function fetchBody(response, request, length) {
    if (!response.body) return;
    const media = (response.headers.get("content-type") || "").split(";", 1)[0].trim().toLowerCase();
    if (media === "text/event-stream" || readers >= 4) return omitted;
    try {
      const reader = response.clone().body.getReader();
      const limit = request.limit;
      const bytes = new Uint8Array(limit);
      let offset = 0;
      readers++;
      return new Promise(resolve => {
        let stopped = false;
        const timer = setTimeout(() => finish({ kind: "timedOut" }, true), 2000);
        // 在任何终态及时释放计数，不等待 tee 的另一支路。
        function finish(value, cancel) {
          if (stopped) return;
          stopped = true;
          clearTimeout(timer);
          request.cancelReader = undefined;
          readers--;
          if (cancel) reader.cancel().catch(() => {});
          resolve(value);
        }
        request.cancelReader = () => finish(undefined, true);
        // 最多保留上限字节，等待一个终态块区分刚好到上限和截断。
        function pump() {
          reader.read().then(({ done, value }) => {
            if (stopped) return;
            if (done) return finish(bytesBody(bytes.subarray(0, offset), offset, limit), false);
            const count = Math.min(value.length, limit - offset);
            bytes.set(value.subarray(0, count), offset);
            offset += count;
            if (value.length > count) {
              return finish(bytesBody(bytes, Math.max(length || 0, offset + 1), limit), true);
            }
            pump();
          }).catch(() => finish(unreadable, true));
        }
        pump();
      });
    } catch (_) { return unreadable; }
  }

  // 只为活跃代次、HTTP URL 和有空余采样预算的调用创建上下文。
  function begin(current, source, method, url, headers, timestamp, started) {
    if (state !== current || requests.size >= 24) return;
    const request = { state: current, source, method, url: url.href, requestHeaders: headers,
      limit: current.limit, timestamp, started, id: ++nextID };
    requests.add(request);
    request.expiry = setTimeout(() => retire(request), 120000);
    return request;
  }

  // 释放单条采样的计时器与附加监听，不修改页面本身的请求。
  function retire(request) {
    if (!requests.delete(request)) return;
    clearTimeout(request.expiry);
    request.removeListeners?.();
    request.cancelReader?.();
  }

  // 网络终态与正文采样独立。按请求发起时的令牌提交且只提交一次。
  function complete(request, fields, durationMs = Math.max(0, Math.round(performance.now() - request.started))) {
    if (!requests.has(request) || request.finished) return;
    request.finished = true;
    request.removeListeners?.();
    Promise.all([request.body, fields.responseBody]).then(([requestBody, responseBody]) => {
      if (!requests.has(request) || state !== request.state) return;
      return postSample(request.state, request.id, {
        source: request.source, method: request.method, url: request.url,
        timestamp: request.timestamp, durationMs, requestHeaders: request.requestHeaders,
        ...fields, requestBody, responseBody
      });
    }).catch(() => {}).finally(() => retire(request));
  }

  // 提交页面观测，过大正文沿用 HTTP 上传预算。
  function postSample(current, id, sample, receipt) {
    let payload = JSON.stringify(sample);
    if (encoder.encode(payload).length > 5 * 1024 * 1024) {
      if (sample.requestBody) sample.requestBody = tooLarge;
      if (sample.responseBody) sample.responseBody = tooLarge;
      payload = JSON.stringify(sample);
    }
    const fields = { token: current.token, id, payload };
    if (!receipt) return postBridge("record", fields);
    const summary = { ...sample };
    if (summary.requestBody) summary.requestBody = tooLarge;
    if (summary.responseBody) summary.responseBody = tooLarge;
    return postBridge("mockHit", { ...fields, receipt, summary: JSON.stringify(summary) });
  }


  if (typeof originalFetch === "function") {
    // 在候选请求发送前决定是否 Mock，非候选走原生 fetch。
    globalThis.fetch = function (input, init) {
      const current = state;
      const run = () => Reflect.apply(originalFetch, this, arguments);
      if (!current) return run();
      const timestamp = Date.now();
      const started = performance.now();
      let request, key, signal, candidate;
      try {
        const inputRequest = input instanceof Request;
        if (init && ![Object.prototype, null].includes(Object.getPrototypeOf(init))) return run();
        const address = inputRequest
          ? Object.getOwnPropertyDescriptor(Request.prototype, "url").get.call(input)
          : input instanceof URL ? URL.prototype.toString.call(input) : input;
        const url = requestURL(address);
        if (!url) return run();
        let method = dataProperty(init, "method") ??
          (inputRequest ? Object.getOwnPropertyDescriptor(Request.prototype, "method").get.call(input) : "GET");
        if (typeof method !== "string") return run();
        if (/^(delete|get|head|options|post|put)$/i.test(method)) method = method.toUpperCase();
        const rawHeaders = dataProperty(init, "headers") ??
          (inputRequest ? Object.getOwnPropertyDescriptor(Request.prototype, "headers").get.call(input) : undefined);
        request = begin(current, "android-webview-fetch", method, url, captureHeaders(headerPairs(rawHeaders)), timestamp, started);
        if (request && !/^(GET|HEAD)$/.test(method)) {
          const body = dataProperty(init, "body");
          request.body = body != null ? requestBody(body, request)
            : inputRequest && Object.getOwnPropertyDescriptor(Request.prototype, "body").get.call(input) ? omitted : undefined;
        }
        key = mockKey(current, method, url);
        if (key) {
          // 使用浏览器验证常规 RequestInit，保留不支持的流和请求体的原有行为。
          const body = dataProperty(init, "body");
          const plainInit = !init || Reflect.ownKeys(init).every(name => {
            const descriptor = Object.getOwnPropertyDescriptor(init, name);
            return descriptor && Object.prototype.hasOwnProperty.call(descriptor, "value");
          });
          if (!plainInit || body instanceof ReadableStream || (inputRequest && (input.bodyUsed || input.body?.locked))) key = undefined;
          else {
            const normalized = inputRequest && input.body
              ? new Request(url.href, { method, mode: input.mode, credentials: input.credentials,
                  redirect: input.redirect, headers: input.headers, signal: input.signal, ...init })
              : new Request(input, init);
            if (normalized.mode === "no-cors" || /text\/event-stream/i.test(normalized.headers.get("Accept") || "") ||
                (inputRequest && input.body && /^(GET|HEAD)$/.test(normalized.method))) key = undefined;
            signal = normalized.signal;
            if (key) candidate = mockCandidate(current, request, "android-webview-fetch", method, url,
              captureHeaders(normalized.headers), timestamp, started);
          }
        }
      } catch (_) { key = undefined; }
      if (!candidate) return observeFetch(run(), request);

      if (signal.aborted) return observeFetch(run(), request);
      return fetchMockDecision(current, key, candidate, signal).then(mock => {
        if (!mock) return observeFetch(run(), request);
        return waitForMock(mock.delayMs, signal).then(() => {
          recordMockHit(candidate, mock, "http");
          return mock.response;
        }, error => {
          recordMockHit(candidate, mock, "abort");
          throw error;
        });
      }, error => {
        if (request) complete(request, { result: "abort" });
        throw error;
      });
    };
  }

  if (xhr) {
    const { open, setRequestHeader, send, abort } = xhr;
    // 先发布请求元数据供 OPENED 回调使用；原生状态未变时补发合成请求的状态转换。
    xhr.open = function (method, url, async) {
      const old = xhrRequests.get(this);
      const previous = detachXHRMock(this);
      const needsOpenedEvent = previous && previous.mock.readyState !== 1 && this.readyState === 1;
      const resumeTimeout = old?.restoreTimeout?.();
      const record = { headers: [] };
      if ((async === undefined || async) && typeof method === "string"
          && (typeof url === "string" || url instanceof URL)) {
        try {
          const safeURL = requestURL(typeof url === "string" ? url : URL.prototype.toString.call(url));
          if (safeURL) {
            record.url = safeURL.href;
            record.method = /^(delete|get|head|options|post|put)$/i.test(method) ? method.toUpperCase() : method;
          }
        } catch (_) {}
      }
      xhrRequests.set(this, record);
      let result;
      try {
        result = Reflect.apply(open, this, arguments);
      } catch (error) {
        if (xhrRequests.get(this) === record) {
          if (old) xhrRequests.set(this, old);
          else xhrRequests.delete(this);
          restoreXHRMock(this, previous);
          resumeTimeout?.();
        } else {
          discardXHRMock(previous);
          if (old?.active && !old.active.finished) retire(old.active);
        }
        throw error;
      }
      discardXHRMock(previous);
      if (old?.active && !old.active.finished) retire(old.active);
      // WebKit 的 open 只在底层状态变化时派发事件。保留原生回调重入后的新请求。
      if (needsOpenedEvent && xhrRequests.get(this) === record && this.readyState === 1) {
        dispatchEvent.call(this, new Event("readystatechange"));
      }
      return result;
    };
    // 已发送的模拟请求遵守 XHR 的 Header 修改时机。
    xhr.setRequestHeader = function (name, value) {
      if (xhrMocks.has(this)) throw new DOMException("Request already sent", "InvalidStateError");
      const result = Reflect.apply(setRequestHeader, this, arguments);
      const record = xhrRequests.get(this);
      if (record && record.headers.length < 64 && typeof name === "string" && typeof value === "string"
          && name.length + value.length <= 8192) record.headers.push([name, value]);
      return result;
    };
    // 候选请求先在桥接器核验，真实链路最多执行一次。
    xhr.send = function (body) {
      if (xhrMocks.has(this)) throw new DOMException("Request already sent", "InvalidStateError");
      const current = state;
      const record = xhrRequests.get(this);
      if (!current || !record?.url || record.active) return Reflect.apply(send, this, arguments);
      let request;
      try {
        const url = requestURL(record.url);
        if (url) {
          const headers = captureHeaders(record.headers);
          const timestamp = Date.now();
          const started = performance.now();
          request = begin(current, "android-webview-xhr", record.method, url, headers, timestamp, started);
          if (request) {
            record.active = request;
            if (!/^(GET|HEAD)$/.test(record.method)) request.body = requestBody(body, request);
          }
          const key = headers.accept?.includes("text/event-stream") ? undefined : mockKey(current, record.method, url);
          if (key && sendXHRMock(this, record, request, current, key,
              () => mockCandidate(current, request, "android-webview-xhr", record.method, url, headers, timestamp, started), () => {
            if (request && requests.has(request)) observeXHR(this, record, request);
            return Reflect.apply(send, this, arguments);
          })) return;
          if (request) observeXHR(this, record, request);
        }
      } catch (_) { if (request) retire(request); }
      try {
        return Reflect.apply(send, this, arguments);
      } catch (error) {
        if (request) retire(request);
        throw error;
      }
    };
    // 桥接或延迟期间取消时，保持一次终态，且不再发出真实请求。
    xhr.abort = function () {
      const mock = xhrMocks.get(this);
      if (!mock) return Reflect.apply(abort, this, arguments);
      if (mock.done) {
        mock.readyState = 0;
        mock.status = 0;
        return;
      }
      finishXHRMock(this, xhrRequests.get(this), mock, "abort");
    };
  }

  // XHR 用终态事件读取浏览器提供的结果，实例复用时丢弃旧监听。
  function observeXHR(target, record, request) {
    const events = ["load", "error", "timeout", "abort", "loadend"];
    function terminal(event) {
      if (xhrRequests.get(target) !== record || record.active !== request) return;
      try {
        if ((event.type !== "load" && event.type !== "loadend") || !target.status) {
          complete(request, { result: event.type === "timeout" || event.type === "abort" ? event.type : "error" });
          return;
        }
        const duration = Math.max(0, Math.round(performance.now() - request.started));
        const pairs = target.getAllResponseHeaders().split(/\r?\n/).flatMap(line => {
          const colon = line.indexOf(":");
          return colon > 0 ? [[line.slice(0, colon), line.slice(colon + 1).trim()]] : [];
        });
        const responseHeaders = captureHeaders(pairs);
        let responseBody;
        switch (target.responseType) {
        case "":
        case "text": responseBody = textBody(target.responseText, request.limit); break;
        case "json": responseBody = target.response == null ? undefined : derivedBody(JSON.stringify(target.response), request.limit); break;
        case "blob":
        case "arraybuffer": responseBody = requestBody(target.response, request); break;
        default: responseBody = omitted;
        }
        complete(request, { result: "http", status: target.status, responseHeaders, responseBody }, duration);
      } catch (_) { retire(request); }
    }
    request.removeListeners = () => {
      for (const name of events) EventTarget.prototype.removeEventListener.call(target, name, terminal, true);
      if (record.active === request) record.active = undefined;
      request.removeListeners = undefined;
    };
    for (const name of events) EventTarget.prototype.addEventListener.call(target, name, terminal, true);
  }
