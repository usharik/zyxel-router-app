# Firebase, AdMob, UMP и Play Billing приносят свои правила в AAR.
# Читаемые стеки Crashlytics: имена файлов и строк сохраняются, mapping загружает плагин Crashlytics.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Telemetry отправляет имя класса ошибки (событие app_error, RedactedException): в release оно должно остаться читаемым.
-keepnames class com.usharik.zyxelrouter.RouterException
-keepnames class com.usharik.zyxelrouter.RouterException$*
# Room 2.2.5 приходит транзитивно (AdMob → WorkManager) и создаёт WorkDatabase_Impl через рефлексию. Его собственное
# правило «-keep class * extends RoomDatabase» в полном режиме R8 (по умолчанию в AGP 9) не сохраняет конструктор,
# и release падал при запуске: «Failed to create an instance of androidx.work.impl.WorkDatabase».
-keep class * extends androidx.room.RoomDatabase { <init>(); }
