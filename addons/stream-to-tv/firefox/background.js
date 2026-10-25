// Stream to TV, background script.
//
// Watches every tab's requests for HLS playlists (.m3u8), remembering the newest URL and the browser headers the
// site may check (User-Agent, Referer, Origin). "Send to TV" posts one to the Stream to TV app on the TV. From
// then on, whenever that tab's page fetches the same playlist with a renewed token, the new URL goes to the TV
// too, so playback there outlives the site's short-lived tokens. Cookies are never sent.

const KEPT_HEADERS = ["user-agent", "referer", "origin"];

// tabId -> Map(key -> {url, headers, seen})
const streams = new Map();
// The stream the TV is playing: {tabId, key, lastUrl, renewals, lastRenewal, title}
let linked = null;

function streamKey(url) {
  const u = new URL(url);
  return u.host + u.pathname;
}

function isPlaylist(url) {
  try {
    return new URL(url).pathname.toLowerCase().endsWith(".m3u8");
  } catch (e) {
    return false;
  }
}

async function settings() {
  const s = await browser.storage.local.get({ address: "", pin: "" });
  return { address: s.address.trim().replace(/^https?:\/\//, "").replace(/\/+$/, ""), pin: s.pin.trim() };
}

async function tv(path, body) {
  const { address, pin } = await settings();
  if (!address) throw new Error("Set the TV's address and PIN in the add-on's settings.");
  const init = body === undefined
    ? { method: "GET" }
    : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ pin, ...body }) };
  let res;
  try {
    res = await fetch(`http://${address}${path}`, { ...init, signal: AbortSignal.timeout(5000) });
  } catch (e) {
    throw new Error(`Can't reach the TV at ${address}. Is Stream to TV open on it?`);
  }
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error === "wrong PIN" ? "Wrong PIN: check it on the TV." : (json.error || `TV answered ${res.status}`));
  return json;
}

function updateBadge(tabId) {
  const n = streams.get(tabId)?.size || 0;
  const live = linked && linked.tabId === tabId;
  browser.browserAction.setBadgeText({ tabId, text: live ? "TV" : n ? String(n) : "" });
  browser.browserAction.setBadgeBackgroundColor({ tabId, color: live ? "#34C759" : "#666" });
}

browser.webRequest.onSendHeaders.addListener(
  (details) => {
    if (details.tabId < 0 || !isPlaylist(details.url)) return;
    const headers = {};
    for (const h of details.requestHeaders || []) {
      if (KEPT_HEADERS.includes(h.name.toLowerCase())) headers[h.name] = h.value;
    }
    const key = streamKey(details.url);
    if (!streams.has(details.tabId)) streams.set(details.tabId, new Map());
    const tabStreams = streams.get(details.tabId);
    const known = tabStreams.get(key);
    tabStreams.set(key, { url: details.url, headers, seen: Date.now(), first: known ? known.first : Date.now() });
    updateBadge(details.tabId);

    if (linked && linked.tabId === details.tabId && linked.key === key && linked.lastUrl !== details.url) {
      linked.lastUrl = details.url;
      tv("/token", { url: details.url })
        .then(() => {
          linked.renewals++;
          linked.lastRenewal = Date.now();
          linked.error = null;
        })
        .catch((e) => {
          if (linked) linked.error = e.message;
        });
    }
  },
  { urls: ["<all_urls>"] },
  ["requestHeaders"]
);

browser.tabs.onUpdated.addListener((tabId, change) => {
  // A new page in the tab starts a new list; the linked stream keeps going on the TV.
  if (change.url && !(linked && linked.tabId === tabId)) {
    streams.delete(tabId);
    updateBadge(tabId);
  }
});

browser.tabs.onRemoved.addListener((tabId) => {
  streams.delete(tabId);
  if (linked && linked.tabId === tabId) linked.error = "The tab was closed, so no more token renewals.";
});

browser.runtime.onMessage.addListener(async (msg) => {
  switch (msg.type) {
    case "state": {
      const list = [...(streams.get(msg.tabId) || new Map()).entries()]
        .map(([key, s]) => ({ key, url: s.url, seen: s.seen, first: s.first }))
        .sort((a, b) => a.first - b.first);
      let tvStatus = null, tvError = null;
      try {
        tvStatus = await tv("/status");
      } catch (e) {
        tvError = e.message;
      }
      return { streams: list, linked, tvStatus, tvError, configured: !!(await settings()).address };
    }
    case "send": {
      const s = streams.get(msg.tabId)?.get(msg.key);
      if (!s) throw new Error("That stream is gone; reload the page.");
      await tv("/play", { url: s.url, headers: s.headers, title: msg.title || "" });
      const previous = linked;
      linked = { tabId: msg.tabId, key: msg.key, lastUrl: s.url, renewals: 0, lastRenewal: null, error: null, title: msg.title };
      if (previous && previous.tabId !== msg.tabId) updateBadge(previous.tabId);
      updateBadge(msg.tabId);
      return linked;
    }
    case "stop": {
      const tabId = linked?.tabId;
      linked = null;
      if (tabId !== undefined) updateBadge(tabId);
      await tv("/stop", {});
      return true;
    }
    case "test":
      return tv("/status");
  }
});
