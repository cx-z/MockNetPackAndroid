  const xhrMocks = new WeakMap();
  const dispatchEvent = EventTarget.prototype.dispatchEvent;
  const addListener = EventTarget.prototype.addEventListener;
  const removeListener = EventTarget.prototype.removeEventListener;
  const nativeTimeout = xhr && Object.getOwnPropertyDescriptor(xhr, "timeout");
  const uploadPrototype = globalThis.XMLHttpRequestUpload?.prototype;
  const uploadEvents = ["loadstart", "progress", "abort", "error", "load", "timeout", "loadend"];
  const observedUploads = new WeakSet();
  if (uploadPrototype) {
    // 记录页面对上传事件的订阅。
    Object.defineProperty(uploadPrototype, "addEventListener", {
      configurable: true, writable: true,
      value: function (type, listener) {
        const result = Reflect.apply(addListener, this, arguments);
        if (listener != null && (typeof type !== "string" || uploadEvents.includes(type))) observedUploads.add(this);
        return result;
      },
    });
  }

  // 对上传事件有依赖的请求保留浏览器真实传输。
  function usesUploadEvents(target) {
    return !uploadPrototype || observedUploads.has(target.upload) ||
      uploadEvents.some(name => target.upload["on" + name] != null);
  }
  const setTimer = globalThis.setTimeout.bind(globalThis);
  const cancelTimer = globalThis.clearTimeout.bind(globalThis);

  // 原请求只在候选不存在或桥接未接受本次命中时执行。
  function mockKey(current, method, url) {
    const key = method.toUpperCase() + " " + url.origin + url.pathname;
    return current.mockKeys.has(key) ? key : undefined;
  }

  // 兜底限制桥接等待时间，过期后的迟到回包不能接管请求。
  async function lookupMock(current, key, candidate) {
    let timer;
    try {
      const reply = await Promise.race([
        postBridge("mock", { token: current.token, key, id: candidate.id, method: candidate.method,
          url: candidate.url, source: candidate.source }),
        new Promise(resolve => { timer = setTimer(resolve, 250); }),
      ]);
      if (state !== current || !reply?.receipt) return;
      const headers = new Headers(reply.headers);
      for (const name of ["Content-Length", "Content-Encoding", "Transfer-Encoding", "Set-Cookie", "Set-Cookie2"]) {
        headers.delete(name);
      }
      const method = key.split(" ", 1)[0];
      const noBody = method === "HEAD" || reply.status === 204 || reply.status === 205;
      const response = new Response(noBody || !reply.body ? null : reply.body, { status: reply.status, headers });
      return { ...reply, response, headers: response.headers, body: noBody ? "" : reply.body };
    } catch (_) {
      return undefined;
    } finally {
      cancelTimer(timer);
    }
  }

  // 正文采样受独立预算约束，候选上下文始终能提交轻量的命中记录。
  function mockCandidate(current, request, source, method, url, requestHeaders, timestamp, started) {
    return { current, request, id: request?.id ?? ++nextID, source, method, url: url.href, requestHeaders,
      timestamp, started };
  }

  // 先提交回执再通知业务，终态回调重入 open 不会吞掉已执行的命中。
  function recordMockHit(candidate, mock, result) {
    if (candidate.reported) return;
    candidate.reported = true;
    if (candidate.request) retire(candidate.request);
    if (state !== candidate.current || !mock) return;
    try {
      const { id, source, method, url, requestHeaders, timestamp, started, current, request } = candidate;
      const body = request?.body;
      const sample = { source, method, url, requestHeaders, timestamp, result,
        requestBody: body && typeof body.then === "function" ? omitted : body,
        durationMs: Math.max(0, Math.round(performance.now() - started)) };
      if (result === "http") {
        sample.status = mock.status;
        sample.responseHeaders = captureHeaders(mock.headers);
        sample.responseBody = !request && mock.body.length > 4096 ? tooLarge : textBody(mock.body, current.limit);
      }
      postSample(current, id, sample, mock.receipt).catch(() => {});
    } catch (_) {}
  }

  // 观测真实响应，业务调用方继续使用原对象。
  function observeFetch(response, request) {
    if (!request) return response;
    return response.then(value => {
      try {
        if (!requests.has(request)) return value;
        if (!value.status || /^opaque/.test(value.type)) complete(request, { result: "opaque" });
        else {
          const duration = Math.max(0, Math.round(performance.now() - request.started));
          const responseHeaders = captureHeaders(value.headers);
          const lengthHeader = value.headers.get("content-length");
          const length = /^[0-9]{1,15}$/.test(lengthHeader || "") ? Number(lengthHeader) : 0;
          const body = fetchBody(value, request, length);
          complete(request, { result: "http", status: value.status, responseHeaders, responseBody: body }, duration);
        }
      } catch (_) { retire(request); }
      return value;
    }, error => {
      try {
        complete(request, { result: error?.name === "AbortError" ? "abort" : "error" });
      } catch (_) { retire(request); }
      throw error;
    });
  }

  function fetchMockDecision(current, key, candidate, signal) {
    return new Promise((resolve, reject) => {
      const abort = () => reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
      signal.addEventListener("abort", abort, { once: true });
      if (signal.aborted) abort();
      lookupMock(current, key, candidate).then(resolve, reject).finally(() => {
        signal.removeEventListener("abort", abort);
      });
    });
  }

  // 延迟等待使用调用方 AbortSignal，保留自定义取消原因。
  function waitForMock(delay, signal) {
    return new Promise((resolve, reject) => {
      if (signal.aborted) { reject(signal.reason ?? new DOMException("Aborted", "AbortError")); return; }
      const abort = () => {
        cancelTimer(timer);
        reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
      };
      const timer = setTimer(() => {
        signal.removeEventListener("abort", abort);
        resolve();
      }, delay);
      signal.addEventListener("abort", abort, { once: true });
    });
  }

  // 暂时露出 WebView 原生属性，让 open 验证参数并触发 OPENED。
  function detachXHRMock(target) {
    const mock = xhrMocks.get(target);
    if (!mock) return;
    xhrMocks.delete(target);
    const properties = mock.properties.map(name => [name, Object.getOwnPropertyDescriptor(target, name)]);
    for (const [name] of properties) delete target[name];
    return { mock, properties };
  }

  // open 参数验证失败时仍保留原请求，重入安装的请求由外层保留。
  function restoreXHRMock(target, previous) {
    if (!previous) return;
    for (const [name, descriptor] of previous.properties) Object.defineProperty(target, name, descriptor);
    xhrMocks.set(target, previous.mock);
  }

  // 成功替换请求后释放上一条合成调用，包括尚在延迟中的已接受命中。
  function discardXHRMock(previous) {
    if (!previous) return;
    const { mock } = previous;
    cancelTimer(mock.timer);
    if (mock.reply) recordMockHit(mock.candidate, mock.reply, "abort");
    else if (mock.request && !mock.request.finished) retire(mock.request);
  }

  // 合成属性只在有效响应状态下暴露正文；取消或超时会先将状态码清零。
  function installXHRResponse(target, record, pending, reply) {
    const readText = () => pending.status && pending.readyState >= 3 ? reply?.body ?? "" : "";
    const getHeaderPairs = () => pending.status && reply ? Array.from(reply.headers) : [];
    let json = null;
    const getResponse = () => {
      if (target.responseType !== "json") return readText();
      return pending.status && pending.readyState === 4 ? json : null;
    };
    if (reply?.body) {
      try { json = JSON.parse(reply.body); } catch (_) {}
    }
    const properties = {
      readyState: { get: () => pending.readyState },
      status: { get: () => pending.status },
      statusText: { get: () => pending.status ? reply?.response.statusText ?? "" : "" },
      responseURL: { get: () => pending.status ? record.url : "" },
      response: { get: getResponse },
      responseText: { get() {
        if (target.responseType && target.responseType !== "text") throw new DOMException("Invalid responseType", "InvalidStateError");
        return readText();
      } },
      responseXML: { get: () => null },
      getResponseHeader: { value: name => pending.status ? reply?.headers.get(name) ?? null : null },
      getAllResponseHeaders: { value: () => getHeaderPairs().map(([k, v]) => k + ": " + v + "\r\n").join("") },
    };
    try {
      for (const [key, descriptor] of Object.entries(properties)) {
        if (Object.prototype.hasOwnProperty.call(target, key)) throw new TypeError();
        Object.defineProperty(target, key, { ...descriptor, configurable: true });
        pending.properties.push(key);
      }
      return true;
    } catch (_) {
      for (const key of pending.properties.splice(0)) delete target[key];
      return false;
    }
  }

  // 响应头和正文回调可以取消或替换请求；完整回包只在进入 DONE 时入账。
  function finishXHRMock(target, record, pending, reason, reply) {
    if (xhrMocks.get(target) !== pending || pending.done) return;
    cancelTimer(pending.timer);
    const request = pending.request;
    const current = () => xhrMocks.get(target) === pending && xhrRequests.get(target) === record;
    if (reason === "load") {
      pending.status = reply.status;
      for (const value of (reply.body ? [2, 3] : [2])) {
        pending.readyState = value;
        dispatchEvent.call(target, new Event("readystatechange"));
        if (!current() || pending.done) return;
      }
      recordMockHit(pending.candidate, reply, "http");
    } else {
      pending.status = 0;
      if (pending.reply) recordMockHit(pending.candidate, pending.reply, reason);
      else if (request) complete(request, { result: reason });
    }
    pending.done = true;
    pending.readyState = 4;
    dispatchEvent.call(target, new Event("readystatechange"));
    if (!current()) return;
    const loaded = reason === "load" ? encoder.encode(reply.body).length : 0;
    dispatchEvent.call(target, new ProgressEvent(reason, { loaded, total: loaded, lengthComputable: reason === "load" }));
    if (!current()) return;
    dispatchEvent.call(target, new ProgressEvent("loadend", { loaded, total: loaded, lengthComputable: reason === "load" }));
    if (reason === "abort" && current()) pending.readyState = 0;
  }

  // 原生兜底只获得本次 send 剩余的时间，页面仍能读写原生 timeout 配置。
  function sendWithRemainingTimeout(target, record, started, deadline, sendReal) {
    if (!deadline) return sendReal();
    let configured = deadline;
    const elapsed = () => Math.floor(performance.now() - started);
    nativeTimeout.set.call(target, Math.max(1, deadline - elapsed()));
    const events = ["load", "error", "abort", "timeout", "loadend"];
    const restore = () => {
      for (const name of events) removeListener.call(target, name, restore, true);
      delete target.timeout;
      nativeTimeout.set.call(target, configured);
      record.restoreTimeout = undefined;
      return activate;
    };
    // Native 的计时从真实 send 算起，open 抛错时恢复同一个剩余额度。
    let timeout = nativeTimeout.get.call(target);
    const activate = () => {
      nativeTimeout.set.call(target, timeout);
      record.restoreTimeout = restore;
      Object.defineProperty(target, "timeout", {
        configurable: true,
        get: () => configured,
        set(value) {
          nativeTimeout.set.call(target, value);
          configured = nativeTimeout.get.call(target);
          timeout = configured;
        }
      });
      for (const name of events) addListener.call(target, name, restore, true);
    };
    activate();
    try { return sendReal(); } catch (error) { restore(); throw error; }
  }

  // 候选等待期间记录 abort/timeout，确认命中后才开始合成回包。
  function sendXHRMock(target, record, request, current, key, makeCandidate, sendReal) {
    if (!nativeTimeout || Object.prototype.hasOwnProperty.call(target, "timeout") || usesUploadEvents(target) ||
        !["", "text", "json"].includes(target.responseType) || target.readyState !== 1) return false;
    const candidate = makeCandidate();
    const started = candidate.started;
    let deadline = target.timeout;
    const pending = { candidate, request, readyState: 1, status: 0, done: false, properties: [] };
    if (!installXHRResponse(target, record, pending)) return false;
    xhrMocks.set(target, pending);
    if (deadline) pending.timer = setTimer(() => finishXHRMock(target, record, pending, "timeout"),
      Math.max(0, deadline - (performance.now() - started)));
    // 先安装占位属性以保证 pending 期间 send/abort 的可观测状态。
    void lookupMock(current, key, candidate).then(reply => {
      if (xhrMocks.get(target) !== pending || pending.done) return;
      cancelTimer(pending.timer);
      deadline = target.timeout;
      const remaining = deadline ? deadline - (performance.now() - started) : Infinity;
      if (remaining <= 0) return finishXHRMock(target, record, pending, "timeout");
      for (const name of pending.properties.splice(0)) delete target[name];
      if (!reply || usesUploadEvents(target) || !["", "text", "json"].includes(target.responseType) ||
          !installXHRResponse(target, record, pending, reply)) {
        xhrMocks.delete(target);
        try { sendWithRemainingTimeout(target, record, started, deadline, sendReal); } catch (_) {
          if (request) complete(request, { result: "error" });
          dispatchEvent.call(target, new ProgressEvent("error"));
          dispatchEvent.call(target, new ProgressEvent("loadend"));
        }
        return;
      }
      pending.reply = reply;
      dispatchEvent.call(target, new ProgressEvent("loadstart"));
      if (xhrMocks.get(target) !== pending || pending.done) return;
      if (reply.delayMs >= remaining) {
        pending.timer = setTimer(() => finishXHRMock(target, record, pending, "timeout"), remaining);
      } else {
        pending.timer = setTimer(() => finishXHRMock(target, record, pending, "load", reply), reply.delayMs);
      }
    }).catch(() => {});
    return true;
  }
