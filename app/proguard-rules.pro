# R8 rules for the release build.

# Readable stack traces in crash reports: keep file names and line numbers (the class and
# method names are restored from the mapping file R8 writes).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# anthropic-java pulls in OpenTelemetry, whose annotation-only compile deps are absent at runtime.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$CopyAnnotations

# anthropic-java (de)serializes requests and responses with Jackson via reflection.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**

# JDK reflection types Android doesn't have, referenced by anthropic-java's structured-output
# schema helpers (victools jsonschema). The debug build carries the same references unused.
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedArrayType
-dontwarn java.lang.reflect.AnnotatedWildcardType
-dontwarn java.lang.reflect.AnnotatedTypeVariable
