# kotlinx.serialization keep rules — required so encode/decode survive R8.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class it.allard.regexphone.**$$serializer { *; }
-keepclassmembers class it.allard.regexphone.** {
    *** Companion;
}
-keepclasseswithmembers class it.allard.regexphone.** {
    kotlinx.serialization.KSerializer serializer(...);
}
