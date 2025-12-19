# ProGuard rules for BossTerm Compose Desktop
# https://github.com/ktorio/ktor-documentation/blob/main/codeSnippets/snippets/proguard/proguard.pro

# ==================== Kotlin ====================
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-keep class kotlin.text.RegexOption { *; }

# ==================== Kotlinx Coroutines ====================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.flow.**inlined**
-dontwarn kotlinx.atomicfu.**

# ==================== Kotlinx Serialization ====================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep Serializable classes and their generated serializers
-keep,includedescriptorclasses class ai.rever.bossterm.**$$serializer { *; }
-keepclassmembers class ai.rever.bossterm.** {
    *** Companion;
}
-keepclasseswithmembers class ai.rever.bossterm.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep @Serializable classes
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# ==================== Ktor ====================
# Keep Ktor service providers (fixes ServiceConfigurationError)
-keep class io.ktor.** { *; }
-keep class io.ktor.serialization.kotlinx.** { *; }
-keep class io.ktor.serialization.kotlinx.json.** { *; }
-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }

# Keep service provider files
-keepnames class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider

# Ktor client engines
-keep class io.ktor.client.engine.** { *; }

# ==================== SLF4J / Logging ====================
-keep class org.slf4j.** { *; }
-keep class ch.qos.** { *; }
-dontwarn org.slf4j.**
-dontwarn ch.qos.**

# ==================== JNA (for native notifications) ====================
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# ==================== ICU4J (for grapheme handling) ====================
-keep class com.ibm.icu.** { *; }
-dontwarn com.ibm.icu.**

# ==================== Java Service Loaders ====================
# Keep all META-INF/services implementations
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ==================== Suppress Warnings ====================
-dontwarn io.netty.**
-dontwarn com.typesafe.**
-dontwarn org.apache.**
-dontwarn javax.**
-dontwarn java.lang.instrument.**
-dontwarn sun.misc.Signal
-dontwarn reactor.blockhound.**

# Ktor/kotlinx.io optional dependencies
-dontwarn kotlinx.io.**
-dontwarn io.ktor.network.**
-dontwarn io.ktor.websocket.**
-dontwarn io.ktor.utils.io.**
-dontwarn io.ktor.server.**

# Android/mobile platform classes not available on desktop
-dontwarn android.**
-dontwarn dalvik.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Optional compression libraries
-dontwarn org.brotli.**
-dontwarn com.aayushatharva.brotli4j.**

# Java instrumentation
-dontwarn java.lang.management.**
-dontwarn javax.management.**

# Missing coroutines debug classes
-dontwarn kotlinx.coroutines.debug.**

# Kotlin concurrent atomics (Kotlin 2.1+ multiplatform classes)
-dontwarn kotlin.concurrent.atomics.**

# Compose internal
-dontwarn androidx.compose.**
