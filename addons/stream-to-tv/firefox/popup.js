// Stream to TV popup: the streams the current tab has loaded, a Send button for each, and the live link.

const $ = (id) => document.getElementById(id);
// The last Send that failed, shown under its row until the next Send: {key, message}.
let sendError = null;

function ago(t) {
  const s = Math.round((Date.now() - t) / 1000);
  return s < 60 ? `${s}s ago` : `${Math.round(s / 60)}m ago`;
}

function label(url) {
  const u = new URL(url);
  return u.host + u.pathname.replace(/^(.{0,12}).*(\/[^/]+)$/, "$1…$2");
}

async function render() {
  const [tab] = await browser.tabs.query({ active: true, currentWindow: true });
  const st = await browser.runtime.sendMessage({ type: "state", tabId: tab.id });

  const tvEl = $("tv");
  if (!st.configured) {
    tvEl.className = "error";
    tvEl.textContent = "Open Settings and enter the address and PIN shown on the TV.";
  } else if (st.tvError) {
    tvEl.className = "error";
    tvEl.textContent = st.tvError;
  } else {
    tvEl.className = "ok";
    const s = st.tvStatus;
    tvEl.textContent = `TV connected · ${s.state}${s.title ? " · " + s.title : ""}`;
    if (s.slow) {
      const w = document.createElement("div");
      w.className = "error";
      w.textContent = `The TV downloads this stream more slowly than it plays (${s.downloadMbps} Mbit/s, it needs ` +
        `${s.streamMbps}), so it will pause every few seconds. Another server on the site may be faster.`;
      tvEl.append(w);
    }
  }

  const l = st.linked;
  const linkedEl = $("linked");
  linkedEl.hidden = !l;
  if (l) {
    linkedEl.textContent = "";
    const line = document.createElement("div");
    line.textContent = l.tabId === tab.id
      ? `Sending this tab's token renewals to the TV: ${l.renewals} so far${l.lastRenewal ? ", last " + ago(l.lastRenewal) : ""}.`
      : "The TV is playing a stream from another tab.";
    linkedEl.append(line);
    if (l.error) {
      const e = document.createElement("div");
      e.className = "error";
      e.textContent = l.error;
      linkedEl.append(e);
    }
    const stop = document.createElement("button");
    stop.className = "secondary";
    stop.textContent = "Stop on TV";
    stop.onclick = async () => {
      await browser.runtime.sendMessage({ type: "stop" }).catch(() => {});
      render();
    };
    linkedEl.append(stop);
  }

  const list = $("streams");
  list.textContent = "";
  list.className = "";
  if (!st.streams.length) {
    list.className = "muted";
    list.textContent = "None yet. Start the video on the page; if it is already playing, reload the page.";
  }
  for (const s of st.streams) {
    const row = document.createElement("div");
    row.className = "stream";
    const name = document.createElement("div");
    name.className = "name";
    name.title = s.url;
    name.textContent = label(s.url);
    const age = document.createElement("div");
    age.className = "age";
    age.textContent = ago(s.seen);
    const send = document.createElement("button");
    send.textContent = l && l.tabId === tab.id && l.key === s.key ? "Resend" : "Send to TV";
    send.onclick = async () => {
      send.disabled = true;
      send.textContent = "Sending…";
      sendError = null;
      try {
        await browser.runtime.sendMessage({ type: "send", tabId: tab.id, key: s.key, title: tab.title });
      } catch (e) {
        sendError = { key: s.key, message: e.message };
      }
      render();
    };
    row.append(name, age, send);
    list.append(row);
    if (sendError && sendError.key === s.key) {
      const e = document.createElement("div");
      e.className = "error";
      e.textContent = sendError.message;
      list.append(e);
    }
  }
}

$("settings").onclick = (e) => {
  e.preventDefault();
  browser.runtime.openOptionsPage();
};
// Refresh while the popup is open, each time after the last refresh has finished.
async function poll() {
  await render().catch(() => {});
  setTimeout(poll, 3000);
}
poll();
