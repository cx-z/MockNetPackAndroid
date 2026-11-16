  const sendBeacon = globalThis.Navigator?.prototype.sendBeacon;
  if (typeof sendBeacon === "function") {
    Navigator.prototype.sendBeacon = function (address, body) {
      const current = state || departingState;
      if (!current) return Reflect.apply(sendBeacon, this, arguments);
      const timestamp = Date.now();
      const started = performance.now();
      const queued = Reflect.apply(sendBeacon, this, arguments);
      try {
        const url = requestURL(address instanceof URL ? URL.prototype.toString.call(address) : address);
        if (!url) return queued;
        const durationMs = Math.max(0, Math.round(performance.now() - started));
        const sample = { source: "android-webview-beacon", method: "POST", url: url.href,
          timestamp, durationMs, requestHeaders: {}, result: queued ? "h5BeaconQueued" : "h5BeaconRejected" };
        if (body instanceof Blob && departingState === current) {
          // 卸载时立即提交排队结果，异步 Blob 读取可能来不及完成。
          sample.requestBody = body.size ? unreadable : undefined;
        } else {
          sample.requestBody = requestBody(body, current);
        }
        if (sample.requestBody instanceof Promise) {
          const request = begin(current, sample.source, sample.method, url, {}, timestamp, started);
          if (request) {
            request.body = sample.requestBody;
            complete(request, { result: sample.result }, durationMs);
          }
        } else {
          postSample(current, ++nextID, sample).catch(() => {});
        }
      } catch (_) {}
      return queued;
    };
  }

  const OriginalEventSource = globalThis.EventSource;
  if (typeof OriginalEventSource === "function") {
    globalThis.EventSource = new Proxy(OriginalEventSource, {
      construct(target, args, newTarget) {
        const current = state;
        if (!current) return Reflect.construct(target, args, newTarget);
        const timestamp = Date.now();
        const started = performance.now();
        const stream = Reflect.construct(target, args, newTarget);
        let request;
        try {
          const url = requestURL(stream.url);
          if (!url) return stream;
          request = begin(current, "android-webview-eventsource", "GET", url, {}, timestamp, started);
          if (!request) return stream;
          // 第一次 open/error 就给出结果，WebView 自己处理流、关闭和重连。
          const terminal = event => complete(request, { result: event.type === "open" ? "h5SSEOpen" : "h5SSEError" });
          const add = EventTarget.prototype.addEventListener;
          const remove = EventTarget.prototype.removeEventListener;
          request.removeListeners = () => {
            remove.call(stream, "open", terminal);
            remove.call(stream, "error", terminal);
            request.removeListeners = undefined;
          };
          add.call(stream, "open", terminal);
          add.call(stream, "error", terminal);
        } catch (_) { if (request) retire(request); }
        return stream;
      }
    });
  }

  const earlyResourceEvents = [];
  const reportedTimings = new WeakSet();
  const resourceInitiators = new Set(["script", "link", "css", "img", "image", "svg", "early-hints"]);
  const pendingTimings = new Set();
  const pendingResourceErrors = new Map();
  const resourceQueue = [];
  let drainingResources = false;
  let timingFlush;

  // 资源常集中返回，依次交给桥接器，避免占满大正文样本的并发预算。
  async function drainResources() {
    if (drainingResources) return;
    drainingResources = true;
    try {
      while (resourceQueue.length) {
        const { current, sample } = resourceQueue.shift();
        if (current !== state) continue;
        try { await postSample(current, ++nextID, sample); } catch (_) {}
      }
    } finally { drainingResources = false; }
  }

  // DOM 与时间轴提供加载结果和图片类型，详情页可以用原 URL 预览。
  function reportResource({ url, timing, timestamp, result, blockedDirective, imageResource }) {
    const current = state;
    if (!current || timestamp < current.startedAt || resourceQueue.length >= 256) return;
    if (timing) reportedTimings.add(timing);
    const sample = { source: "android-webview-resource", method: "GET", url, timestamp,
      durationMs: timing ? Math.round(timing.duration) : 0, requestHeaders: {},
      result, status: timing?.responseStatus || undefined, blockedDirective,
      imageResource: imageResource || ["img", "image", "svg"].includes(timing?.initiatorType) || undefined };
    resourceQueue.push({ current, sample });
    void drainResources();
  }

  // 从文档已有时间轴补读资源，已关联 DOM 事件的条目只提交一次。
  function flushResourceTimings() {
    timingFlush = undefined;
    const timings = Array.from(pendingTimings);
    pendingTimings.clear();
    if (!state) return;
    for (const timing of timings) {
      if (!resourceInitiators.has(timing.initiatorType) || reportedTimings.has(timing)) continue;
      const timestamp = performance.timeOrigin + timing.startTime;
      if (timestamp < state.startedAt) continue;
      const url = requestURL(timing.name);
      if (!url) continue;
      const result = "h5ResourceObserved";
      reportResource({ url: url.href, timing, timestamp, result });
    }
  }

  // 给资源元素的 load/error 事件一个机会优先关联同一条时间轴记录。
  function scheduleResourceTimings() {
    if (state && timingFlush === undefined) timingFlush = setTimeout(flushResourceTimings, 150);
  }

  // 保留观察器收到的条目，即使浏览器的 Resource Timing 缓冲区已满。
  function collectResourceTimings(timings) {
    const current = state;
    if (!current) return;
    for (const timing of timings) {
      if (pendingTimings.size >= 256) break;
      if (performance.timeOrigin + timing.startTime >= current.startedAt &&
          resourceInitiators.has(timing.initiatorType) && !reportedTimings.has(timing)) pendingTimings.add(timing);
    }
    scheduleResourceTimings();
  }

  // 首次握手前暂存少量元素结果，握手后仍按会话生效时间过滤。
  function acceptResourceEvent(event) {
    if (event.timing) reportedTimings.add(event.timing);
    if (state) reportResource(event);
    else if (!installed && earlyResourceEvents.length < 64) earlyResourceEvents.push(event);
  }

  // CSP 可能和同一元素的 error 一起到达，合并失败原因和可用耗时。
  function acceptResourceError(event) {
    const current = state;
    const previous = pendingResourceErrors.get(event.url);
    if (previous && previous.state === current) {
      previous.event.blockedDirective ||= event.blockedDirective;
      if (!previous.event.timing && event.timing) {
        previous.event.timing = event.timing;
        previous.event.timestamp = event.timestamp;
      }
      return;
    }
    if (pendingResourceErrors.size >= 128) return;
    const failure = { event, state: current, initial: !installed };
    pendingResourceErrors.set(event.url, failure);
    setTimeout(() => {
      if (pendingResourceErrors.get(event.url) !== failure) return;
      pendingResourceErrors.delete(event.url);
      if (failure.state === state || failure.initial) acceptResourceEvent(failure.event);
    }, 50);
  }

  // 每次握手后补读当前会话内已出现的首屏资源。
  function resumeResourceCapture() {
    for (const event of earlyResourceEvents.splice(0)) reportResource(event);
    collectResourceTimings(performance.getEntriesByType("resource"));
  }

  // 记录常见元素的加载结果，图片保留类型和 WebKit 实际选中的 URL。
  function onResourceEvent(event) {
    if ((!state && installed) || !event.isTrusted) return;
    try {
      const target = event.target;
      let address;
      switch (target?.tagName) {
      case "SCRIPT": address = target.src; break;
      case "LINK":
        if (target.relList.contains("stylesheet") || target.relList.contains("modulepreload") ||
            (target.relList.contains("preload") && ["script", "style", "image"].includes(target.as))) address = target.href;
        break;
      case "IMG": address = target.currentSrc || target.src; break;
      case "image": address = target.href?.baseVal; break;
      }
      if (!address) return;
      const url = requestURL(address);
      if (!url) return;
      const timings = performance.getEntriesByName(url.href, "resource");
      const latest = timings[timings.length - 1];
      // 相同 URL 可由多个元素共享，避免拿旧条目给本次事件计算耗时。
      const timing = latest && !reportedTimings.has(latest) ? latest : undefined;
      const timestamp = timing ? performance.timeOrigin + timing.startTime : Date.now();
      if (event.type === "error") {
        acceptResourceError({ url: url.href, timing, timestamp, result: "h5ResourceError" });
      } else {
        const imageResource = target.tagName === "IMG" || target.tagName === "image" ||
          (target.tagName === "LINK" && target.as === "image");
        acceptResourceEvent({ url: url.href, timing, timestamp, result: "h5ResourceLoaded", imageResource });
      }
    } catch (_) {}
  }

  document.addEventListener("load", onResourceEvent, true);
  document.addEventListener("error", onResourceEvent, true);
  addEventListener("load", () => collectResourceTimings(performance.getEntriesByType("resource")), { once: true });
  document.addEventListener("securitypolicyviolation", event => {
    if ((!state && installed) || !event.isTrusted || event.disposition !== "enforce" ||
        !/^(script-src|style-src|img-src)(-|$)/.test(event.effectiveDirective) ||
        !/^https?:\/\//i.test(event.blockedURI)) return;
    try {
      const url = requestURL(event.blockedURI);
      if (url) acceptResourceError({ url: url.href, timestamp: Date.now(),
        result: "h5ResourceError", blockedDirective: event.effectiveDirective });
    } catch (_) {}
  });
  try {
    new PerformanceObserver(list => {
      if (state) collectResourceTimings(list.getEntries());
    }).observe({ entryTypes: ["resource"] });
  } catch (_) {}


  function reportNavigationWhenReady() {
    if (document.readyState !== "complete") return;
    setTimeout(() => {
      const current = state;
      const timing = performance.getEntriesByType("navigation")[0];
      const timestamp = timing && performance.timeOrigin + timing.startTime;
      if (navigationReported || !current || !timing || timestamp < current.startedAt) return;
      const url = requestURL(timing.name);
      if (!url) return;
      navigationReported = true;
      postSample(current, ++nextID, { source: "android-webview-navigation", method: "UNKNOWN",
        url: url.href, timestamp, durationMs: Math.round(timing.duration),
        result: "h5NavigationFinished", status: timing.responseStatus || undefined }).catch(() => {});
    }, 0);
  }
  addEventListener("load", reportNavigationWhenReady, { once: true });
  refresh();
})();
