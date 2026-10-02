# صدایار (Sedayar)

اپلیکیشن اندرویدی **یادداشت‌برداری صوتی و کلاسی** با حس و حال مدرن؛ حرف بزن تا متن شود، فایل صوتی کلاس را آپلود کن تا پیاده‌سازی و خلاصه شود، با دست‌خط بنویس و حالت کلاس را تجربه کن.

[![Build APK & Release](https://github.com/ariosssss/sedayar/actions/workflows/android.yml/badge.svg)](https://github.com/ariosssss/sedayar/actions/workflows/android.yml)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Language](https://img.shields.io/badge/language-Java-orange)
![Min SDK](https://img.shields.io/badge/minSdk-24-blue)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

## 📥 دانلود مستقیم APK

آخرین نسخه را از صفحهٔ **Releases** دانلود و نصب کن (اندروید ۷ به بالا):

➡️ **[دانلود صدایار از Releases](https://github.com/ariosssss/sedayar/releases/latest)**

---

## صفحه‌های اپ (نوار پایین)

| تب | امکانات |
|---|---|
| 🗒️ **یادداشت‌ها** | لیست کارتی، جستجو، پین، رنگ، اشتراک‌گذاری |
| 🎙️ **گفتار زنده** | دیکتهٔ پیوستهٔ فارسی/انگلیسی با موتور Google، تایمر و متن زنده |
| ✍️ **دست‌نویس** | بوم تمام‌صفحه با ۵ رنگ قلم، ۳ ضخامت، پاک‌کن و Undo/Redo |
| 🎵 **فایل صوتی** | آپلود mp3/m4a/wav/ogg → پیاده‌سازی متن + خلاصه + خروجی TXT/SRT/MD/HTML |

## امکانات ویژه

- **🔥 حالت کلاس:** حین کلاس صدا ضبط می‌شود و هر خط دست‌خط به لحظهٔ صدا وصل می‌شود. در صفحهٔ پخش، نوشته‌ها **دقیقاً در لحظهٔ نوشته‌شدن ظاهر می‌شوند** و با لمس هر خط، صدای استاد در همان ثانیه پخش می‌شود.
- **ضبط مقاوم در کلاس:** خاموش‌شدن صفحه یا عوض‌کردن تب، ضبط را قطع نمی‌کند؛ با خروج از صفحهٔ نوشتن، همه‌چیز خودکار ذخیره می‌شود.
- **پیاده‌سازی فایل صوتی با دو موتور:**
  - **Vosk آفلاین** — مدل فارسی ~۵۳ مگابایت، یک‌بار دانلود (دکمهٔ دانلود در همین تب)، بدون کلید و بدون فیلترشکن
  - **Whisper API** (سازگار با Groq/OpenAI) — دقت بسیار بالاتر برای صدای دور/شلوغ، با کلید خودت
- **خلاصه‌ساز:** خلاصهٔ خودکار آفلاین (رایگان، همیشه در دسترس) + خلاصهٔ هوشمند AI با هر endpoint سازگار با OpenAI
- **خروجی چندفرمته:** TXT، زیرنویس SRT زمان‌بندی‌شده، Markdown و HTML استایل‌دار قابل چاپ
- **تشخیص گفتار زندهٔ مقاوم:** بازسازی خودکار موتور بعد از خطا، پیام دقیق فارسی برای هر خطا، حالت آفلاین گوگل (اختیاری)
- **ظرافت ایرانی:** خوشامدگویی بار اول با نقش ختام (ستارهٔ هشت‌پر) طلایی روی لاجورد، تم فیروزه‌ای/کرم با نقش بسیار کم‌رنگ در پس‌زمینه

## امکانات پایه

- **تبدیل گفتار به متن (Google Speech-to-Text):** دکمه میکروفون را بزن و حرف بزن؛ متن به‌صورت زنده نوشته می‌شود. پشتیبانی از **فارسی و انگلیسی** با یک کلید تغییر زبان.
- **نقاشی و دست‌نویس:** بوم اختصاصی با قلم‌های رنگی، سه ضخامت، پاک‌کن، واگرد (Undo)، بازانجام (Redo) و پاک‌کردن بوم.
- **محیط یادداشت زیبا:** فونت فارسی **وزیرمتن**، تاریخ **شمسی** خودکار، چیدمان راست‌به‌چپ (RTL).
- **جستجو، پین کردن و رنگ کارت‌ها** (مثل Google Keep).
- **اشتراک‌گذاری:** ارسال متن و خروجی **PNG** از نقاشی‌ها (با FileProvider).
- **صدایار پلاس:** صفحه اشتراک با سه پلن که آمادهٔ اتصال به درگاه پرداخت است.
- **ذخیره‌سازی محلی:** همهٔ یادداشت‌ها با Room روی خود گوشی ذخیره می‌شوند؛ ذخیرهٔ خودکار هنگام خروج از ویرایشگر.

## تکنولوژی‌ها

| مورد | انتخاب |
|---|---|
| زبان | Java 17 |
| UI | AndroidX + Material 3 (ViewBinding) |
| دیتابیس | Room 2.6 |
| گفتاربه‌متن زنده | Android `SpeechRecognizer` (موتور Google) |
| پیاده‌سازی فایل صوتی | Vosk 0.3.47 (آفلاین) + Whisper API |
| خلاصه‌ساز | TF استخراجی (آفلاین) + LLM API |
| شبکه | OkHttp 4.12 |
| فونت | Vazirmatn |
| حداقل اندروید | 7.0 (API 24) — تارگت: API 35 |
| بیلد | Gradle 8.9 / AGP 8.7.3 |

## نحوهٔ بیلد و اجرا

1. این ریپو را کلون کن: `git clone https://github.com/ariosssss/sedayar.git`
2. با **Android Studio** (نسخه Koala یا جدیدتر) پوشه پروژه را باز کن و صبر کن تا Gradle Sync تمام شود.
3. از منوی Build گزینه **Build Bundle(s) / APK(s) → Build APK(s)** را بزن، یا با ترمینال:

```bash
./gradlew assembleDebug
# خروجی: app/build/outputs/apk/debug/app-debug.apk
```

4. برای نسخهٔ نهایی: `./gradlew assembleRelease` (قبلش باید signing config خودت را اضافه کنی).

## درباره تشخیص گفتار

- موتور پیش‌فرض `SpeechRecognizer` اندروید همان سرویس گفتار **گوگل** است؛ **رایگان** است و نیازی به API Key و پرداخت ندارد — فقط اینترنت می‌خواهد.
- روی گوشی‌هایی که «Offline speech recognition» را از `Settings → Google → Search → Voice` دانلود کرده‌اند، با روشن‌کردن گزینهٔ **تشخیص گفتار آفلاین** در منوی ویرایشگر، بدون اینترنت هم کار می‌کند.
- اگر روی گوشی، اپ Google نصب/به‌روز نباشد، ممکن است تشخیص گفتار در دسترس نباشد؛ اپ در این حالت دکمهٔ میکروفون را غیرفعال می‌کند.

## صدایار پلاس — اتصال درگاه پرداخت

بخش اشتراک کامل پیاده‌سازی شده و فقط نقطهٔ اتصال پرداخت خالی گذاشته شده تا SDK دلخواهت (کافه‌بازار، مایکت، زرین‌پال و ...) وصل شود:

```java
// PremiumActivity.java
public interface PurchaseListener {
    void onPlanSelected(String planId);
}

public static volatile PurchaseListener purchaseListener;
```

مراحل اتصال:

1. SDK پرداخت را به `app/build.gradle` اضافه کن.
2. قبل از باز شدن صفحهٔ اشتراک، `PremiumActivity.purchaseListener` را ست کن.
3. وقتی `onPlanSelected(planId)` صدا زده شد، فرآیند پرداخت را با یکی از این شناسه‌ها شروع کن:
   - `PremiumManager.PLAN_MONTHLY`
   - `PremiumManager.PLAN_YEARLY`
   - `PremiumManager.PLAN_LIFETIME`
4. بعد از تأیید پرداخت، برای فعال‌سازی اشتراک این را صدا بزن:

```java
PremiumManager.setPremium(context, true, planId);
```

همه‌جای اپ وضعیت پریمیوم را از `PremiumManager.isPremium()` می‌خواند. برای تست هم می‌توانی روی صفحهٔ اشتراک، **۷ بار روی متن نسخه** بزنی تا اشتراک تستی فعال/غیرفعال شود.

## ساختار پروژه

```
sedayar/
├── app/src/main/
│   ├── java/com/sedayar/app/
│   │   ├── SedayarApp.java              ← Application + ریپازیتوری سراسری
│   │   ├── MainActivity.java            ← لیست یادداشت‌ها + جستجو
│   │   ├── NotesAdapter.java            ← آداپتر گرید Keep-مانند
│   │   ├── NoteEditorActivity.java      ← ویرایشگر + گفتاربه‌متن
│   │   ├── PremiumActivity.java         ← اشتراک پلاس
│   │   ├── data/                        ← Note, NoteDao, NotesDb, NotesRepository
│   │   ├── util/                        ← AppPrefs, PremiumManager, DateUtils, NoteColors
│   │   └── view/DrawingView.java        ← بوم نقاشی و دست‌نویس
│   └── res/
│       ├── font/                        ← Vazirmatn
│       ├── layout/                      ← layout ها
│       ├── values/  values-fa/          ← استرینگ‌های دوزبانه (انگلیسی/فارسی)
│       └── mipmap-*/                    ← آیکون‌ها (legacy + adaptive)
├── build.gradle / settings.gradle
└── gradle/wrapper/
```

## مجوزهای استفاده‌شده

| مجوز | دلیل |
|---|---|
| `RECORD_AUDIO` | برای ضبط صدای کاربر جهت تبدیل به متن |
| `INTERNET` | برای ارتباط با سرویس گفتار گوگل (حالت آنلاین) |

## بیلد خودکار با GitHub Actions

فایل [`.github/workflows/android.yml`](.github/workflows/android.yml) هر بار که کد جدیدی به `main` پوش شود یا تگ `v*` بزنی، اجرا می‌شود:

| تریگر | نتیجه |
|---|---|
| push به `main` / اجرای دستی | بیلد APK + آپلود artifact در تب Actions |
| push تگ `v*` (مثل `v1.0.1`) | بیلد APK + ساخت **Release** با فایل قابل دانلود |

برای انتشار نسخهٔ جدید کافی است:

```bash
git tag v1.0.1
git push origin v1.0.1
```

چند دقیقه بعد، Release جدید با APK در صفحهٔ Releases ظاهر می‌شود.

## نقشه راه

- [ ] اتصال درگاه پرداخت (کافه‌بازار / مایکت / زرین‌پال)
- [ ] همگام‌سازی ابری یادداشت‌ها
- [ ] تم تیره و تم‌های رنگی بیشتر
- [ ] ضبط صدا و پیوست فایل صوتی به یادداشت
- [ ] خروجی PDF از یادداشت‌ها

## توسعه‌دهنده

ساخته‌شده با همکاری AI — با ایده و برای گیت‌هاب [ariosssss](https://github.com/ariosssss).

## License

MIT — see [LICENSE](LICENSE).

---

### English summary

**Sedayar** is an Android (Java) voice-note app with a warm, paper-like classic theme: live Google speech-to-text (Persian & English), a smooth drawing/handwriting canvas (colors, widths, eraser, undo/redo), colored & pinned notes with search, PNG sharing via FileProvider, Jalali dates, full RTL + i18n (fa/en), and a ready-to-wire "Sedayar Plus" subscription screen with a clean payment-gateway hook (`PremiumActivity.purchaseListener` + `PremiumManager`). Notes are stored locally with Room and auto-saved.
