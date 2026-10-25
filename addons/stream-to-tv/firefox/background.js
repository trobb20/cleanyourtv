// Stream to TV, background script.
//
// Watches every tab's requests for HLS playlists (a .m3u8 path, or an HLS Content-Type for sites whose playlist
// URLs have no extension, such as ad-inserting proxies), remembering the newest URL and the browser headers the
// site may check (User-Agent, Referer, Origin). "Send to TV" posts one to the Stream to TV app on the TV. From
// then on, whenever a playlist from that tab and host comes with a renewed token, the new URL goes to the TV too,
// so playback there outlives the site's short-lived tokens. Cookies are never sent.

const KEPT_HEADERS = ["user-agent", "referer", "origin"];
// application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl
const PLAYLIST_TYPE = /mpegurl/i;
// Query parameters that change on every playlist poll without being a token: cache-busters and Low-Latency HLS
// delivery directives. A change in one of these alone is not a renewal.
const VOLATILE = /^(_|_hls_[a-z]+|cb|nocache|rnd|rand|random)$/i;
// A renewal reaches the TV at most this often; a newer one waiting meanwhile goes when the gap is over.
const MIN_RENEWAL_GAP_MS = 20000;
const RETRY_MS = 5000, MAX_RETRIES = 6;
// A playlist fetched within this long is one the page is still polling.
const LIVE_MS = 30000;

// tabId -> Map(key -> {url, headers, seen, first})
const streams = new Map();
// requestId -> the kept request headers, from sending until the response says whether it is a playlist
const pending = new Map();
// The stream the TV is playing: {tabId, key, host, params, renewals, lastRenewal, lastSent, pendingUrl, title, error}
let linked = null;
// A PIN the TV refused. It is not sent again until the settings change, so an open popup polling the TV
// does not run into the TV's lockout for wrong PINs.
let refusedPin = null;

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

// The query's key=value pairs, raw as the TV reads them, without the volatile ones.
function tokenParams(url) {
  const out = {};
  const q = url.indexOf("?");
  if (q < 0) return out;
  const hash = url.indexOf("#", q);
  for (const pair of url.slice(q + 1, hash < 0 ? undefined : hash).split("&")) {
    const eq = pair.indexOf("=");
    if (eq > 0 && !VOLATILE.test(pair.slice(0, eq))) out[pair.slice(0, eq)] = pair.slice(eq + 1);
  }
  return out;
}

// The URL with each parameter it carries set to the value in `values`, raw, the rest left as it is.
function withParams(url, values) {
  const q = url.indexOf("?");
  if (q < 0) return url;
  const hash = url.indexOf("#", q);
  const end = hash < 0 ? url.length : hash;
  const query = url.slice(q + 1, end).split("&").map((pair) => {
    const eq = pair.indexOf("=");
    const k = eq > 0 ? pair.slice(0, eq) : null;
    return k !== null && k in values ? `${k}=${values[k]}` : pair;
  });
  return url.slice(0, q + 1) + query.join("&") + url.slice(end);
}

// The playlist picked may be one the page fetched only once, minutes ago (a master playlist), whose token has
// expired since. It goes to the TV with the newest token values from this tab's latest playlist on that host.
function freshen(url, tabStreams) {
  const host = new URL(url).host;
  let newest = null;
  for (const s of tabStreams.values()) {
    if (new URL(s.url).host === host && (!newest || s.seen > newest.seen)) newest = s;
  }
  return newest && newest.url !== url ? withParams(url, tokenParams(newest.url)) : url;
}

async function settings() {
  const s = await browser.storage.local.get({ address: "", pin: "", buffer: 30 });
  return {
    address: s.address.trim().replace(/^https?:\/\//, "").replace(/\/+$/, ""), pin: s.pin.trim(), buffer: s.buffer,
  };
}

browser.storage.onChanged.addListener(() => {
  refusedPin = null;
});

function failure(message, retryable = false) {
  const e = new Error(message);
  e.retryable = retryable;
  return e;
}

async function tv(path, body) {
  const { address, pin } = await settings();
  if (!address) throw failure("Set the TV's address and PIN in the add-on's settings.");
  const pinRefused = pin === refusedPin;
  if (body !== undefined && pinRefused) throw failure("Wrong PIN: check it on the TV and save it in Settings.");
  const init = body === undefined
    ? { method: "GET", headers: pinRefused ? {} : { "X-PIN": pin } }
    : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ pin, ...body }) };
  let res;
  try {
    res = await fetch(`http://${address}${path}`, { ...init, signal: AbortSignal.timeout(5000) });
  } catch (e) {
    throw failure(`Can't reach the TV at ${address}. Is Stream to TV open on it?`, true);
  }
  const json = await res.json().catch(() => ({}));
  if (res.status === 403 && json.error === "wrong PIN") {
    refusedPin = pin;
    throw failure("Wrong PIN: check it on the TV and save it in Settings.");
  }
  if (res.status === 429) throw failure("Too many wrong PINs: wait a minute, then check the PIN on the TV.");
  if (!res.ok) throw failure(json.error || `TV answered ${res.status}`);
  if (pinRefused) throw failure("Wrong PIN: check it on the TV and save it in Settings.");
  return json;
}

function updateBadge(tabId) {
  const n = streams.get(tabId)?.size || 0;
  const live = linked && linked.tabId === tabId;
  browser.browserAction.setBadgeText({ tabId, text: live ? "TV" : n ? String(n) : "" });
  browser.browserAction.setBadgeBackgroundColor({ tabId, color: live ? "#34C759" : "#666" });
}

// A playlist from the linked tab and host: a renewal when a token parameter already seen has a new value.
// Parameters seen for the first time (the media playlist's, after the master was sent) are only remembered.
function noteRenewal(link, url) {
  let renewed = false;
  for (const [k, v] of Object.entries(tokenParams(url))) {
    if (k in link.params && link.params[k] !== v) renewed = true;
    link.params[k] = v;
  }
  if (!renewed) return;
  link.pendingUrl = url;
  if (link.timer) return;
  const wait = Math.max(0, link.lastSent + MIN_RENEWAL_GAP_MS - Date.now());
  link.timer = setTimeout(() => sendRenewal(link, 0), wait);
}

function sendRenewal(link, attempt) {
  link.timer = null;
  if (linked !== link || !link.pendingUrl) return;
  const url = link.pendingUrl;
  link.pendingUrl = null;
  link.lastSent = Date.now();
  tv("/token", { url })
    .then(() => {
      link.renewals++;
      link.lastRenewal = Date.now();
      link.error = null;
    })
    .catch((e) => {
      link.error = e.message;
      // A renewal lost on the way leaves the TV with the old token; try again unless a newer one is waiting.
      if (e.retryable && attempt < MAX_RETRIES && linked === link && !link.pendingUrl && !link.timer) {
        link.pendingUrl = url;
        link.timer = setTimeout(() => sendRenewal(link, attempt + 1), RETRY_MS);
      }
    });
}

browser.webRequest.onSendHeaders.addListener(
  (details) => {
    if (details.tabId < 0) return;
    const headers = {};
    for (const h of details.requestHeaders || []) {
      if (KEPT_HEADERS.includes(h.name.toLowerCase())) headers[h.name] = h.value;
    }
    // After a redirect the same requestId comes back with the new URL, and this replaces the entry.
    pending.set(details.requestId, headers);
  },
  { urls: ["<all_urls>"], types: ["xmlhttprequest", "media", "other"] },
  ["requestHeaders"]
);

browser.webRequest.onErrorOccurred.addListener((details) => pending.delete(details.requestId), { urls: ["<all_urls>"] });

browser.webRequest.onResponseStarted.addListener(
  (details) => {
    const headers = pending.get(details.requestId);
    pending.delete(details.requestId);
    if (!headers || details.statusCode >= 400) return;
    const type = (details.responseHeaders || []).find((h) => h.name.toLowerCase() === "content-type")?.value || "";
    if (isPlaylist(details.url) || PLAYLIST_TYPE.test(type)) record(details, headers);
  },
  { urls: ["<all_urls>"], types: ["xmlhttprequest", "media", "other"] },
  ["responseHeaders"]
);

// A playlist response from a tab: listed for the popup, and a possible renewal for the stream on the TV.
function record(details, headers) {
  const key = streamKey(details.url);
  if (!streams.has(details.tabId)) streams.set(details.tabId, new Map());
  const tabStreams = streams.get(details.tabId);
  const known = tabStreams.get(key);
  tabStreams.set(key, { url: details.url, headers, seen: Date.now(), first: known ? known.first : Date.now() });
  updateBadge(details.tabId);

  if (linked && linked.tabId === details.tabId && new URL(details.url).host === linked.host) {
    noteRenewal(linked, details.url);
  }
}

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

// What the popup shows of the link; the timer and parameters stay here.
function linkView(l) {
  return l && { tabId: l.tabId, key: l.key, renewals: l.renewals, lastRenewal: l.lastRenewal, error: l.error, title: l.title };
}

browser.runtime.onMessage.addListener(async (msg) => {
  switch (msg.type) {
    case "state": {
      // Playlists the page is still fetching come first: one fetched only once (a session start, an old master)
      // may carry a token that has expired since. Within each group, the order they first appeared.
      const now = Date.now();
      const live = (s) => now - s.seen < LIVE_MS;
      const list = [...(streams.get(msg.tabId) || new Map()).entries()]
        .map(([key, s]) => ({ key, url: s.url, seen: s.seen, first: s.first }))
        .sort((a, b) => live(b) - live(a) || a.first - b.first);
      let tvStatus = null, tvError = null;
      try {
        tvStatus = await tv("/status");
      } catch (e) {
        tvError = e.message;
      }
      return { streams: list, linked: linkView(linked), tvStatus, tvError, configured: !!(await settings()).address };
    }
    case "send": {
      const s = streams.get(msg.tabId)?.get(msg.key);
      if (!s) throw new Error("That stream is gone; reload the page.");
      const url = freshen(s.url, streams.get(msg.tabId));
      const { buffer } = await settings();
      await tv("/play", { url, headers: s.headers, title: msg.title || "", bufferSeconds: buffer });
      const previous = linked;
      if (previous?.timer) clearTimeout(previous.timer);
      linked = {
        tabId: msg.tabId, key: msg.key, host: new URL(url).host, params: tokenParams(url),
        renewals: 0, lastRenewal: null, lastSent: 0, pendingUrl: null, timer: null, error: null, title: msg.title,
      };
      if (previous && previous.tabId !== msg.tabId) updateBadge(previous.tabId);
      updateBadge(msg.tabId);
      return linkView(linked);
    }
    case "stop": {
      const tabId = linked?.tabId;
      if (linked?.timer) clearTimeout(linked.timer);
      linked = null;
      if (tabId !== undefined) updateBadge(tabId);
      await tv("/stop", {});
      return true;
    }
    case "test":
      refusedPin = null;
      return tv("/status");
  }
});
