const $ = (id) => document.getElementById(id);

browser.storage.local.get({ address: "", pin: "", buffer: 30 }).then((s) => {
  $("address").value = s.address;
  $("pin").value = s.pin;
  $("buffer").value = String(s.buffer);
});

$("save").onclick = async () => {
  let address = $("address").value.trim();
  if (address && !/:\d+$/.test(address)) address += ":8642";
  await browser.storage.local.set({ address, pin: $("pin").value.trim(), buffer: Number($("buffer").value) });
  $("address").value = address;
  const r = $("result");
  r.className = "muted";
  r.textContent = "Testing…";
  try {
    const s = await browser.runtime.sendMessage({ type: "test" });
    r.className = "ok";
    r.textContent = `Connected: the TV is ${s.state}.`;
  } catch (e) {
    r.className = "error";
    r.textContent = e.message;
  }
};
