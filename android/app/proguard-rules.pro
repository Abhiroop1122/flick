# kotlinx.serialization: keep generated serializers for our wire classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.flick.** {
    *** Companion;
}
-keepclasseswithmembers class app.flick.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.flick.**$$serializer { *; }
# OkHttp ships its own rules; these silence optional-dependency warnings.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
