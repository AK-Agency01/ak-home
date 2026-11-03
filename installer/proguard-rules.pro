# libadb, Conscrypt and BouncyCastle use reflection / JNI
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn javax.annotation.**
