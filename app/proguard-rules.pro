# JNI entry (libdshengine.so looks up this class/method by name)
-keep class io.github.jackwener.dsh.engine.NodeRuntime { native <methods>; }
# Instantiated by Shizuku by class name in the shell process
-keep class io.github.jackwener.dsh.core.platform.ShellUserService { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.jackwener.dsh.**$$serializer { *; }
# pdfbox-android: BouncyCastle (encrypted PDFs) and the JP2/JBIG2 image codecs are optional and not bundled
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.filter.**
# Apache MINA SSHD + net.i2p EdDSA (remote mode, #51): security-provider registrars, the NIO2 I/O factory and the
# Ed25519 provider are loaded by class name; optional integrations (BouncyCastle, APR, JMX, RMI, slf4j bindings) are absent.
-keep class org.apache.sshd.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn org.slf4j.**
-dontwarn net.i2p.crypto.eddsa.**
