# Glance ActionCallback implementations instantiated via reflection by widget clicks.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}

# NextLib native JNI audio decoder bridge.
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }
