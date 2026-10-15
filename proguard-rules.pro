# libadb-android finds Conscrypt by reflection (OpenSSLProvider, Conscrypt.exportKeyingMaterial).
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class io.github.muntashirakon.** { *; }
-dontwarn io.github.muntashirakon.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
