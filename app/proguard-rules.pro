# USB シリアルのドライバーは、対応機器の一覧をリフレクションで調べる (getSupportedDevices)
-keep class com.hoho.android.usbserial.driver.** { *; }

# 保存形式 (kotlinx.serialization) のクラス名・項目名は変えない (バックアップや保存データとの互換)
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.obdmeter.data.**$$serializer { *; }
-keepclassmembers class com.obdmeter.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers enum com.obdmeter.** { *; }
