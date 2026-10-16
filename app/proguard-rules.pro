-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

-keep class app.smallthingz.alldoc.core.NativeDocuments { *; }
-keep interface app.smallthingz.alldoc.core.NativeDocuments$Cancellation { *; }
-keepclassmembers class * implements app.smallthingz.alldoc.core.NativeDocuments$Cancellation { boolean isCancelled(); }
