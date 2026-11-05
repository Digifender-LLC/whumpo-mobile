# R8 rules for the release build. Libraries ship their own consumer rules (OkHttp, Ktor, Credential Manager,
# kotlinx.serialization); these cover what the release job's emulator smoke test showed is needed, and keep
# stack traces readable. Anything added here needs a reason.

# kotlinx.serialization: keep generated serializers and companions of our DTOs (the mobile API wire models).
-keepattributes *Annotation*, InnerClasses, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.whumpo.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.whumpo.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Ktor: JVM-only debug and logging classes it probes but Android does not have.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn io.netty.**
-dontwarn reactor.blockhound.**

# Credential Manager: the Play services provider is instantiated by class name for Android 13 and older.
-keep class androidx.credentials.playservices.** { *; }

# Readable crash reports in Play Console (the mapping file is inside the bundle).
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
