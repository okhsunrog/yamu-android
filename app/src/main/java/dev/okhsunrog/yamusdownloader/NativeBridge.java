package dev.okhsunrog.yamusdownloader;

final class NativeBridge {
    static {
        System.loadLibrary("ya_mus_downloader");
    }

    private NativeBridge() {}

    static native String mediaBackend();

    static native String downloadTrack(String token, String trackReference, String outputDirectory);
}
