# Sedayar ProGuard rules
# Material Components & Room ship their own consumer R8 rules.

# --- Vosk (offline speech recognition via JNI) ---
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn org.slf4j.**

# --- OkHttp ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
