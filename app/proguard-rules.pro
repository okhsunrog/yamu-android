# JNI entry points are exported by their Java names.
-keep class dev.okhsunrog.yamu.NativeBridge { *; }

# The Rust verifier loads these classes by their fully-qualified names through JNI.
-keep class org.rustls.platformverifier.** { *; }
