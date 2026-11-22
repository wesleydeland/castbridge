# R8 keep rules for CastBridge.
#
# The app is plain Java with a single JNI boundary. Every Android entry point
# (Activity, Service, receiver of NsdManager callbacks) is declared in
# AndroidManifest.xml or reached from one, and AGP keeps manifest components
# automatically — so nothing in this file is about keeping the app's own code
# alive, only about keeping the two things R8 cannot see across the JNI
# boundary and the bug-report workflow.

# JNI: native_sender.cpp implements these four methods on
# com.castbridge.CastService, and the library is loaded by symbol name
# (System.loadLibrary("native_sender")). Renaming the Java declarations would
# silently break the cast at runtime, so pin the names even though the default
# Android ruleset also keeps `native` methods.
-keepclasseswithmembernames,includedescriptorclasses class com.castbridge.CastService {
    native <methods>;
}

# Obfuscation is on (R8 default). Keep the source file name and line numbers so
# a stack trace pasted into a GitHub issue still points at a real line.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
