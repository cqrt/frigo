# kotlinx.serialization keeps its generated serializers through reflection on
# the companion objects, which the shrinker cannot see.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class nz.mckenzie.frigo.data.** {
    *** Companion;
}
-keepclasseswithmembers class nz.mckenzie.frigo.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp ships optional platform integrations that are absent on Android.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
