# Hermes Mobile release R8 rules (paired with proguard-android-optimize.txt).
# Preserve runtime diagnostic reason names for clean local troubleshooting while
# allowing aggressive dead-code stripping and optimization across app & push SDKs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute Hermes
