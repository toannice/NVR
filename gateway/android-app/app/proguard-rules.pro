# kotlinx.serialization keeps generated serializers; keep our @Serializable models.
-keepclassmembers class vn.aiview.nvr.gatewayonboard.net.** {
    *** Companion;
}
-keepclasseswithmembers class vn.aiview.nvr.gatewayonboard.net.** {
    kotlinx.serialization.KSerializer serializer(...);
}
