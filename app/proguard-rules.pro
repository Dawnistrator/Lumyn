# Glance ActionCallback implementations instantiated via reflection by widget clicks.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}

# NextLib native JNI audio decoder bridge (targeted to JNI-invoked entry points).
-keep,allowshrinking class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
    native <methods>;
}
-keep,allowshrinking class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary {
    native <methods>;
}
