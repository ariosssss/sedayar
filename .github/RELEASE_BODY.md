# صدایار — نسخهٔ ۱.۱ (v1.1.0)

بازطراحی کامل + موتور جدید پیاده‌سازی فایل صوتی 🎙️📝

## تازه‌های این نسخه

- 🧭 **نوار پایین با ۴ بخش:** یادداشت‌ها | گفتار زنده | دست‌نویس | فایل صوتی
- 🎙️ **گفتار زندهٔ پیوسته:** صفحهٔ اختصاصی با میکروفون بزرگ، تایمر و متن زنده؛ ضبط تا هر وقت خودت بگویی توقف
- ✍️ **دست‌نویس تمام‌صفحه:** تختهٔ بزرگ برای نوشتن متن استاد سر کلاس
- 🔥 **حالت کلاس:** هم‌زمان که صدا ضبط می‌شود دست‌خط بنویس — هر خط به لحظهٔ صدا وصل می‌شود؛ بعداً با لمس همان خط، صدای استاد در همان ثانیه پخش می‌شود!
- 🎵 **فایل صوتی به متن:** mp3/m4a/wav/ogg را آپلود کن و متن را بگیر:
  - موتور **Vosk آفلاین** (مدل فارسی ۵۳ مگابایتی، بدون کلید و بدون فیلترشکن)
  - موتور **Whisper API** اختیاری (Groq/OpenAI یا سرور خودت)
- 📌 **خلاصه‌سازی:** خلاصهٔ خودکار آفلاین + خلاصهٔ هوشمند AI (عنوان و کلیدواژه)
- 📤 **خروجی چندفرمته:** TXT، زیرنویس SRT زمان‌بندی‌شده، Markdown و HTML استایل‌دار قابل چاپ
- 🎨 **ظاهر جدید:** تم مدرن روشن Material 3 با کارت‌های ملایم و گوشه‌های گرد
- 🛠️ **رفع ارور میکروفون:** بازسازی خودکار موتور بعد از خطا + پیام فارسی دقیق برای هر خطا

## نصب

1. فایل `Sedayar-v1.1.0.apk` را از بخش **Assets** پایین همین صفحه دانلود کن.
2. روی گوشی باز کن و اجازهٔ «نصب از منابع ناشناس» را بده (اندروید **۷.۰ به بالا**).
3. برای پیاده‌سازی آفلاین، از **تنظیمات → پیاده‌سازی فایل صوتی** مدل فارسی را یک‌بار دانلود کن.

> نکته: APK با کلید تست (debug) امضا شده؛ برای به‌روزرسانی از v1.0.0 اول نسخهٔ قبلی را حذف کن.

---

## English

**v1.1.0 — the big redesign**

- Bottom navigation with 4 sections: Notes | Live voice | Handwriting | Audio file
- Continuous live dictation screen with a big pulsing mic and timer
- Full-screen handwriting canvas + **Class Mode**: record the lecture while writing — every stroke is linked to the audio moment; tap a stroke during playback to hear the professor at that instant
- Audio file transcription (mp3/m4a/wav/ogg) with **offline Vosk** (53 MB Persian model) or an optional **Whisper-compatible API**
- Summaries: instant offline extractive summary + optional AI summary via any OpenAI-compatible endpoint
- Export as TXT, SRT (timed subtitles), Markdown and printable HTML
- Fresh Material 3 look and fixed mic error handling (auto-retry + precise messages)

**Install:** download `Sedayar-v1.1.0.apk` from Assets (Android 7.0+). For offline transcription, download the Persian model once from Settings. The APK is debug-signed — uninstall v1.0.0 before updating.
