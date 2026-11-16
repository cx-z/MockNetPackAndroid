// Page hooks adapted from the MockNetPackKit 1.2.0 H5 capture and Mock scripts.
(() => {
  "use strict";
  if (globalThis !== globalThis.top || !/^https?:$/.test(location.protocol)) return;
  const bridge = globalThis.__mockNetPackAndroidHTTP;
  if (!bridge || globalThis.__mockNetPackRefreshHTTP) return;

  const sendBridgeMessage = bridge.postMessage.bind(bridge);
  const credential = "__MNP_BRIDGE_CREDENTIAL__";
  const chunkSize = 32 * 1024;
  const pendingMessages = new Map();
  let rpcID = 0;

  // Split large bridge transfers below WebView's IPC buffer size. Only the
  // matching live RPC can assemble a reply, and incomplete replies expire.
  function postBridge(kind, fields) {
    if (pendingMessages.size >= 64) return Promise.reject(new Error("MockNetPack bridge busy"));
    const rpc = ++rpcID;
    const identity = { document: documentID, credential, rpc };
    const envelope = { ...fields, ...identity, kind };
    const payload = envelope.payload;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pendingMessages.delete(rpc);
        reject(new Error("MockNetPack bridge timeout"));
      }, kind === "mock" ? 250 : 1500);
      pendingMessages.set(rpc, { resolve, timer, parts: [], chars: 0 });
      try {
        if (typeof payload !== "string" || payload.length <= chunkSize) {
          sendBridgeMessage(JSON.stringify(envelope));
        } else {
          delete envelope.payload;
          const total = Math.ceil(payload.length / chunkSize);
          for (let part = 0; part < total; part++) {
            sendBridgeMessage(JSON.stringify({ ...envelope, part, total,
              chunk: payload.slice(part * chunkSize, (part + 1) * chunkSize) }));
          }
        }
      } catch (error) {
        clearTimeout(timer);
        pendingMessages.delete(rpc);
        reject(error);
      }
    });
  }

  bridge.onmessage = event => {
    try {
      const reply = JSON.parse(event.data);
      if (reply.document !== documentID) return;
      if (reply.disable) {
        revision++;
        departingState = undefined;
        clearState();
        return;
      }
      if (reply.refresh) { refresh(); return; }
      const pending = pendingMessages.get(reply.rpc);
      if (!pending) return;
      let value = reply.value;
      if (typeof reply.chunk === "string") {
        if (reply.part !== pending.parts.length || reply.total < 2 || reply.total > 192) return;
        pending.chars += reply.chunk.length;
        if (pending.chars > 5 * 1024 * 1024) return;
        pending.parts.push(reply.chunk);
        if (pending.parts.length !== reply.total) return;
        value = JSON.parse(pending.parts.join(""));
      }
      pendingMessages.delete(reply.rpc);
      clearTimeout(pending.timer);
      pending.resolve(value);
    } catch (_) {}
  };
