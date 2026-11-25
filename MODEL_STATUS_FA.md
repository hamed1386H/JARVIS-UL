# وضعیت مدل هوش مصنوعی JARVIS

- مدل پیش‌فرض: `Qwen3-4B-Instruct-2507-Q4_K_M.gguf`
- منبع فایل: مخزن عمومی GGUF مدل Qwen3-4B-Instruct-2507 در Hugging Face (quantized by bartowski).
- حجم تقریبی: 2.50 GB.
- فرمت: GGUF.
- نوع: Instruct، مناسب مکالمه و پیروی از دستور.
- Runtime: llama.cpp native via JNI.
- ABI: arm64-v8a.
- دریافت: دانلود یک‌باره در اولین اجرای برنامه با Android Download Manager.
- استفاده پس از دانلود: کاملاً محلی؛ بدون API مکالمه.
- مدل قدیمی `Qwen3-0.6B-Q4_0.gguf` از نوع Base است و دیگر مدل پیش‌فرض نیست.

توجه: سازگاری نهایی فایل با Runtime و عملکرد روی گوشی باید پس از build و اجرای واقعی بررسی شود. حجم مدل به معنی تضمین سرعت یا کیفیت یکسان در همه دستگاه‌ها نیست.
