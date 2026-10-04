# Shrink but do not rename, so a crash on a test build stays readable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
# Shizuku's process API is reached by reflection.
-keep class rikka.shizuku.Shizuku { *; }
