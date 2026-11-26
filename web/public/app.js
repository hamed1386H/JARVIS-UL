const $ = id => document.getElementById(id);
const messages = $("messages");
const loginDialog = $("loginDialog");
let deferredInstall = null;
let recognition = null;
let busy = false;

function setStatus(text) { $("status").textContent = text; }
function addMessage(role, text) {
  const row = document.createElement("div");
  row.className = "message " + role;
  const avatar = document.createElement("div");
  avatar.className = "avatar";
  avatar.textContent = role === "assistant" ? "J" : "ش";
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  const sender = document.createElement("span");
  sender.className = "sender";
  sender.textContent = role === "assistant" ? "JARVIS" : "YOU";
  const p = document.createElement("p");
  p.textContent = text;
  bubble.append(sender, p); row.append(avatar, bubble); messages.append(row);
  messages.scrollTop = messages.scrollHeight;
  return p;
}
async function api(url, options = {}) {
  const response = await fetch(url, { credentials: "same-origin", ...options, headers: { "Content-Type": "application/json", ...(options.headers || {}) } });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || "خطای ارتباط با سرور");
  return data;
}
async function refreshStatus() {
  try {
    const data = await api("/api/me");
    $("connection").classList.add("ready");
    $("connection").innerHTML = "<i></i> متصل به مغز";
    $("brainStatus").textContent = data.brainReady ? "مغز آنلاین آماده پاسخ‌گویی است." : "سرور وصل است، اما کلید مدل هوش مصنوعی هنوز تنظیم نشده است.";
    $("modelName").textContent = data.model || "—";
    $("providerName").textContent = data.provider || "—";
    if (!data.brainReady) setStatus("سرور متصل است؛ مغز هوش مصنوعی نیاز به پیکربندی دارد");
    return true;
  } catch (_) {
    $("connection").classList.remove("ready");
    $("connection").innerHTML = "<i></i> ورود لازم است";
    loginDialog.showModal();
    return false;
  }
}
async function loadHistory() {
  try {
    const data = await api("/api/history");
    if (data.messages?.length) {
      messages.replaceChildren();
      data.messages.forEach(m => addMessage(m.role, m.content));
    }
  } catch (_) {}
}
async function sendMessage(text) {
  const value = text.trim();
  if (!value || busy) return;
  addMessage("user", value);
  $("messageInput").value = "";
  busy = true; $("sendBtn")?.setAttribute("disabled", "true");
  setStatus("جارویس در حال فکر کردن است...");
  const pending = addMessage("assistant", "در حال پردازش...");
  try {
    const data = await api("/api/chat", { method: "POST", body: JSON.stringify({ message: value }) });
    pending.textContent = data.reply;
    setStatus("پاسخ آماده است");
    speak(data.reply);
  } catch (err) {
    pending.textContent = err.message;
    setStatus("ارتباط با مغز با خطا روبه‌رو شد");
  } finally {
    busy = false; $("sendBtn")?.removeAttribute("disabled"); messages.scrollTop = messages.scrollHeight;
  }
}
$("composer").addEventListener("submit", e => { e.preventDefault(); sendMessage($("messageInput").value); });
$("loginForm").addEventListener("submit", async e => {
  e.preventDefault(); $("loginError").textContent = "";
  try {
    await api("/api/login", { method: "POST", body: JSON.stringify({ password: $("passwordInput").value }) });
    loginDialog.close(); $("passwordInput").value = "";
    await refreshStatus(); await loadHistory();
  } catch (err) { $("loginError").textContent = err.message; }
});
$("settingsBtn").addEventListener("click", async () => { await refreshStatus(); $("settingsDialog").showModal(); });
$("closeSettings").addEventListener("click", () => $("settingsDialog").close());
$("logoutBtn").addEventListener("click", async () => {
  try { await api("/api/logout", { method: "POST", body: "{}" }); } catch (_) {}
  $("settingsDialog").close(); loginDialog.showModal();
});
$("clearBtn").addEventListener("click", () => {
  // UI-only clear: persistent shared history remains intact to avoid accidental deletion.
  messages.replaceChildren();
  addMessage("assistant", "گفتگوی روی صفحه پاک شد. تاریخچه مشترک سرور حفظ شده است.");
});
function speak(text) {
  if (!("speechSynthesis" in window)) return;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = "fa-IR"; utterance.rate = 1;
  window.speechSynthesis.speak(utterance);
}
const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
if (SpeechRecognition) {
  recognition = new SpeechRecognition();
  recognition.lang = "fa-IR"; recognition.interimResults = false; recognition.maxAlternatives = 1;
  recognition.onstart = () => { $("micBtn").classList.add("listening"); setStatus("در حال شنیدن صدای تو..."); };
  recognition.onend = () => $("micBtn").classList.remove("listening");
  recognition.onerror = e => { $("micBtn").classList.remove("listening"); setStatus("تشخیص صدا ناموفق بود: " + e.error); };
  recognition.onresult = e => { const text = e.results?.[0]?.[0]?.transcript || ""; if (text) sendMessage(text); };
} else { $("micBtn").title = "تشخیص گفتار در این مرورگر پشتیبانی نمی‌شود"; }
$("micBtn").addEventListener("click", () => {
  if (!recognition) { setStatus("برای تشخیص صدا از Chrome به‌روز استفاده کن."); return; }
  try { recognition.start(); } catch (_) { recognition.stop(); }
});
window.addEventListener("beforeinstallprompt", e => { e.preventDefault(); deferredInstall = e; $("installBtn").hidden = false; });
$("installBtn").addEventListener("click", async () => { if (deferredInstall) { deferredInstall.prompt(); deferredInstall = null; $("installBtn").hidden = true; } });
if ("serviceWorker" in navigator) window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js").catch(() => {}));
refreshStatus().then(ok => { if (ok) loadHistory(); });
