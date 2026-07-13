package dev.okhsunrog.yamusdownloader;

import android.content.Context;

final class NativeBridge {
    static {
        System.loadLibrary("ya_mus_downloader");
    }

    private NativeBridge() {}

    static native void initialize(Context context);

    static native String mediaBackend();

    static native String requestDeviceCode();

    static native String pollDeviceToken(String deviceCode);

    static native String downloadProgress();

    static native void cancelDownload();

    static native String downloadResource(
            String token,
            String resourceReference,
            String outputDirectory,
            boolean preferMp3
    );
}
