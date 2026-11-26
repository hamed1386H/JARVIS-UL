const express = require("express");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const Database = require("better-sqlite3");
const { rateLimit } = require("express-rate-limit");

const app = express();
app.disable("x-powered-by");
app.set("trust proxy", 1);
app.use(express.json({ limit: "32kb" }));

const PORT = Number(process.env.PORT || 3000);
const ACCESS_PASSWORD = process.env.JARVIS_ACCESS_PASSWORD || "";
const AI_BASE_URL = (process.env.AI_BASE_URL || "https://api.groq.com/openai/v1").replace(/\/+$/, "");
const AI_API_KEY = process.env.AI_API_KEY || "";
const AI_MODEL = process.env.AI_MODEL || "openai/gpt-oss-20b";
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, "data");
fs.mkdirSync(DATA_DIR, { recursive: true });
const db = new Database(path.join(DATA_DIR, "jarvis.sqlite"));
db.pragma("journal_mode = WAL");
db.exec(`
  CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    role TEXT NOT NULL CHECK(role IN ('user','assistant')),
    content TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  );
`);
const insertMessage = db.prepare("INSERT INTO messages(role, content) VALUES (?, ?)");
const getHistory = db.prepare("SELECT role, content, created_at FROM messages ORDER BY id DESC LIMIT ?");
const sessions = new Map();

function safeEqual(a, b) {
  const aa = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return aa.length === bb.length && crypto.timingSafeEqual(aa, bb);
}
function readCookie(req, name) {
  const raw = req.headers.cookie || "";
  const part = raw.split(";").map(x => x.trim()).find(x => x.startsWith(name + "="));
  return part ? decodeURIComponent(part.slice(name.length + 1)) : "";
}
function auth(req, res, next) {
  const token = readCookie(req, "jarvis_session");
  const expires = sessions.get(token);
  if (!token || !expires || expires < Date.now()) {
    if (token) sessions.delete(token);
    return res.status(401).json({ error: "برای ورود، رمز دسترسی جارویس را وارد کن." });
  }
  sessions.set(token, Date.now() + 7 * 24 * 60 * 60 * 1000);
  next();
}
const loginLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 10, standardHeaders: "draft-7", legacyHeaders: false });
const chatLimiter = rateLimit({ windowMs: 60 * 1000, limit: 20, standardHeaders: "draft-7", legacyHeaders: false });

app.post("/api/login", loginLimiter, (req, res) => {
  if (!ACCESS_PASSWORD) return res.status(503).json({ error: "سرور هنوز رمز JARVIS_ACCESS_PASSWORD را تنظیم نکرده است." });
  const password = typeof req.body?.password === "string" ? req.body.password : "";
  if (!safeEqual(password, ACCESS_PASSWORD)) return res.status(401).json({ error: "رمز ورود اشتباه است." });
  const token = crypto.randomBytes(32).toString("hex");
  sessions.set(token, Date.now() + 7 * 24 * 60 * 60 * 1000);
  res.setHeader("Set-Cookie", `jarvis_session=${encodeURIComponent(token)}; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=604800`);
  res.json({ ok: true });
});
app.post("/api/logout", auth, (req, res) => {
  sessions.delete(readCookie(req, "jarvis_session"));
  res.setHeader("Set-Cookie", "jarvis_session=; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=0");
  res.json({ ok: true });
});
app.get("/api/me", auth, (req, res) => res.json({
  ok: true,
  brainReady: Boolean(AI_API_KEY && AI_MODEL),
  model: AI_MODEL,
  provider: AI_BASE_URL.includes("groq.com") ? "Groq" : "OpenAI-compatible"
}));
app.get("/api/history", auth, (req, res) => {
  const rows = getHistory.all(80).reverse();
  res.json({ messages: rows });
});
app.delete("/api/history", auth, (req, res) => {\n  db.prepare("DELETE FROM messages").run();\n  res.json({ ok: true });\n});\napp.post("/api/chat", auth, chatLimiter, async (req, res) => {
  const text = typeof req.body?.message === "string" ? req.body.message.trim() : "";
  if (!text || text.length > 8000) return res.status(400).json({ error: "پیام خالی است یا بیش از ۸۰۰۰ نویسه دارد." });
  if (!AI_API_KEY) return res.status(503).json({ error: "مغز آنلاین هنوز روی سرور پیکربندی نشده؛ مدیر باید AI_API_KEY را در تنظیمات سرور ثبت کند." });

  const previous = getHistory.all(12).reverse().map(m => ({ role: m.role, content: m.content }));
  const messages = [
    { role: "system", content: "تو JARVIS هستی؛ دستیار فارسی‌زبان، دقیق و مسئولیت‌پذیر. مگر کاربر زبان دیگری بخواهد، فارسی روان پاسخ بده. کوتاه و کاربردی باش. هرگز ادعا نکن فرمانی روی گوشی اجرا شده مگر برنامه آن را تأیید کرده باشد. اطلاعات محرمانه و کلیدهای API را درخواست نکن." },
    ...previous,
    { role: "user", content: text }
  ];
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 65000);
  try {
    const upstream = await fetch(AI_BASE_URL + "/chat/completions", {
      method: "POST",
      headers: { "Authorization": "Bearer " + AI_API_KEY, "Content-Type": "application/json", "Accept": "application/json" },
      body: JSON.stringify({ model: AI_MODEL, messages, temperature: 0.35, stream: false }),
      signal: controller.signal
    });
    const payload = await upstream.json().catch(() => ({}));
    if (!upstream.ok) {
      const detail = payload?.error?.message || "سرویس هوش مصنوعی پاسخ موفق نداد.";
      return res.status(502).json({ error: "خطای سرویس هوش مصنوعی: " + String(detail).slice(0, 240) });
    }
    const answer = payload?.choices?.[0]?.message?.content;
    const normalized = typeof answer === "string" ? answer.trim() :
      Array.isArray(answer) ? answer.map(p => p?.text || "").join("").trim() : "";
    if (!normalized) return res.status(502).json({ error: "پاسخ مدل خالی بود." });
    insertMessage.run("user", text);
    insertMessage.run("assistant", normalized.slice(0, 12000));
    res.json({ reply: normalized });
  } catch (err) {
    const message = err.name === "AbortError" ? "پاسخ‌گویی بیش از حد طول کشید؛ دوباره تلاش کن." : "ارتباط با مغز جارویس برقرار نشد.";
    res.status(502).json({ error: message });
  } finally {
    clearTimeout(timeout);
  }
});

app.use(express.static(path.join(__dirname, "public"), { extensions: ["html"] }));
app.get("*", (req, res) => res.sendFile(path.join(__dirname, "public", "index.html")));
app.listen(PORT, "0.0.0.0", () => {
  console.log(`JARVIS web listening on ${PORT}; model=${AI_MODEL}; keyConfigured=${Boolean(AI_API_KEY)}`);
});
