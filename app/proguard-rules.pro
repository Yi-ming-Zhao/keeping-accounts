-keep class com.dokar.quickjs.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
# Keep the Kotlin host and persistence contracts callable across the APK/test boundary.
# Framework UI code remains shrinkable; these packages are also the native agent boundary.
-keep class dev.localledger.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.room.** { *; }
-keep class androidx.sqlite.** { *; }
-keep class androidx.tracing.** { *; }
# Markdown is also verified from the separately installed release instrumentation APK.
-keep class io.noties.markwon.** { *; }
