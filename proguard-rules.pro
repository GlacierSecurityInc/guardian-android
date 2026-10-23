-keep class eu.siacs.conversations.**
-keep class im.conversations.**

# ExtensionFactory instantiates XMPP model classes (Extension/StreamElement
# subclasses annotated with @XmlElement) reflectively via their no-arg
# constructor when parsing incoming XML (see ExtensionFactory.create). Since
# that's the only call site, R8 treats the constructor as unused and strips
# it, causing a runtime NoSuchMethodException/IllegalStateException such as
# "im.conversations.android.xmpp.model.streams.Features has no default
# constructor". Keep those constructors explicitly.
-keepclassmembers class * extends im.conversations.android.xmpp.model.Extension {
    <init>();
}

# Overflow menu icons are forced visible via reflection in XmppActivity
# (menu.getClass().getSimpleName() == "MenuBuilder" + setOptionalIconsVisible).
# Without this, R8 renames the class/method and icons silently disappear in
# release builds while still showing in debug (unminified) builds.
-keep class androidx.appcompat.view.menu.MenuBuilder {
    public void setOptionalIconsVisible(boolean);
}

-keepnames class com.amazonaws.**
-keepnames class com.amazon.**

# CoreConnectionActivity.readTunnelStats() reads the running tunnel's counters
# reflectively (backend.getClass().getMethod("getStatistics", Tunnel.class), then
# totalRx()/totalTx() on the returned Statistics). Nothing calls getStatistics
# statically and the tunnel AAR ships no consumer rules, so R8 deleted
# com.wireguard.android.backend.Statistics, Statistics$PeerStats and
# GoBackend.getStatistics(Tunnel) outright -- verified in
# build/outputs/mapping/release/usage.txt. readTunnelStats() catches the resulting
# NoSuchMethodException and returns null, so the session throughput readout and
# sparkline on the Core Connection screen silently read as unavailable on every
# release build while working in debug.
-keep class com.wireguard.android.backend.** { *; }

# classifyAuthError() in OkHttpClientProvider, isCredentialFailure() in
# ReauthenticateAccountDialog and isIncorrectCurrentPasswordError() in
# MyProfileActivity all branch on Throwable.getClass().getSimpleName() matched
# against Cognito exception names. Amplify's own exceptions keep their names via
# the -keep on com.amplifyframework.** below, but UserNotFoundException,
# InvalidParameterException, CodeMismatchException and ExpiredCodeException exist
# only in the AWS Kotlin SDK, which R8 renames (mapping.txt: UserNotFoundException
# -> cb5, InvalidParameterException -> ae2, ExpiredCodeException -> wg1). Those
# checks can never fire on a release build, so a deleted Cognito user or an expired
# code classifies as UNKNOWN and routes into silent retry instead of prompting
# reauthentication -- the device then never obtains a JWT and reports nothing to
# the console. Names only; members stay shrinkable.
-keepnames class aws.sdk.kotlin.services.cognitoidentityprovider.model.**

# libsignal-client's Java classes are JNI bridges to the Rust core (see
# org.signal.libsignal.internal.Native): method/field names and signatures
# must match what the native library looks up, and R8 renaming/stripping
# would turn that into a runtime UnsatisfiedLinkError/NoSuchMethodError.
-keep class org.signal.libsignal.** { *; }

# SQLCipher deliberately has no rule here. net.zetetic:sqlcipher-android ships a consumer
# proguard.txt inside the AAR, which AGP applies automatically, and it is tighter than
# anything written by hand: it keeps native <methods>, public <init>(...) and the
# `long mNativeHandle` field across net.zetetic.**, plus the SQLiteCustomFunction and
# SQLiteDebug$PagerStats members the native side reads by name. Adding a blanket
# `-keep class net.zetetic.** { *; }` would only defeat shrinking of a library that
# already protects the parts that matter. Verify with usage.txt after minifyRelease if
# a native lookup ever fails.

# Keep these classes
-keep class com.amazonaws.services.**.*Handler   # Request handlers defined in request.handlers
# The negated filter excludes the fake backend. Without it, the wildcard keeps the class
# shells of com.glaciersecurity.glaciermessenger.http.fake in a store APK -- R8 drops their
# methods (nothing reaches them once BuildConfig.FAKE_BACKEND folds to false) but the class
# names and the seeded fixture strings survive, which is dead weight and confusing to anyone
# disassembling a release build. Order does not matter in a ProGuard class filter, but the
# negated entry must come first to be applied.
-keep class !com.glaciersecurity.glaciermessenger.http.fake.**,com.glaciersecurity.glaciermessenger.**
-keep class com.google.android.gms.**
-keep class com.twilio.common.** { *; }
-keep class com.twilio.video.** { *; }
-keep class tvi.webrtc.** { *; }
# Twilio Programmable Voice
-keep class com.twilio.** { *; }
-keep class tvo.webrtc.** { *; }
-dontwarn tvo.webrtc.**
-keep class com.twilio.voice.** { *; }

-keep class com.soundcloud.android.crop.**

-keep class com.google.android.gms.**

-keep class org.openintents.openpgp.*
-keep class org.webrtc.** { *; }
# webrtc 149+ native code looks up org.jni_zero.JniZero by name when the library loads;
# stripped, every call aborts the process with "JNI DETECTED ERROR ... java_class == null".
-keep class org.jni_zero.** { *; }
# generated stub the AAR does not ship; only JniZero.setJniClassLoader() refers to it
-dontwarn org.jni_zero.JniZeroJni
# libjniPdfium throws PdfPasswordException and builds util.Size by name for password-protected PDFs
-keep class com.shockwave.pdfium.** { *; }

-dontwarn javax.mail.internet.MimeMessage
-dontwarn javax.mail.internet.MimeBodyPart
-dontwarn javax.mail.internet.SharedInputStream
-dontwarn javax.activation.DataContentHandler
-dontwarn org.bouncycastle.mail.**
-dontwarn org.bouncycastle.x509.util.LDAPStoreHelper
-dontwarn org.bouncycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.bouncycastle.cert.dane.**
-dontwarn rocks.xmpp.addr.**
-dontwarn com.google.firebase.analytics.connector.AnalyticsConnector
-dontwarn java.lang.**
-dontwarn javax.lang.**

-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn org.jetbrains.annotations.**

-keepclassmembers class eu.siacs.conversations.http.services.** {
  !transient <fields>;
}

-keepclassmembers class com.glaciersecurity.glaciermessenger.http.services.** {
  !transient <fields>;
}

# Same reason, for the Retrofit/Gson models added when the mobile API moved to Retrofit.
# `-keep class com.glaciersecurity.glaciermessenger.**` above keeps these classes but NOT
# their members, so R8 was free to rename and delete fields Gson only ever touches
# reflectively. Response DTOs happened to survive because app code reads their fields and
# @SerializedName pins their JSON names. SecurityInfoUpdateRequest had neither: its three
# fields are assigned in the constructor and never read, so R8 removed all of them and Gson
# serialised `{}` — the security-info PUT went out with an empty body on every release build
# (7.2, 7.2.1, 7.2.2-RC1) while debug builds worked fine. Verified via
# build/outputs/mapping/release/mapping.txt: the class was listed with zero retained fields.
-keepclassmembers class com.glaciersecurity.glaciermessenger.http.mobile.** {
  !transient <fields>;
}
# Needed for proper GSON deserialization
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.Type

# Retrofit does reflection on generic parameters. InnerClasses is required to use Signature and
# EnclosingMethod is required to use InnerClasses.
-keepattributes Signature, InnerClasses, EnclosingMethod

# Retrofit does reflection on method and parameter annotations.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Retain service method parameters when optimizing.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Ignore annotation used for build tooling.
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# Ignore JSR 305 annotations for embedding nullability information.
-dontwarn javax.annotation.**

# Guarded by a NoClassDefFoundError try/catch and only used when on the classpath.
-dontwarn kotlin.Unit

# Top-level functions that can only be used by Kotlin.
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*


# With R8 full mode, it sees no subtypes of Retrofit interfaces since they are created with a Proxy
# and replaces all potential values with null. Explicitly keeping the interfaces prevents this.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# Keep inherited services.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>

# With R8 full mode generic signatures are stripped for classes that are not
# kept. Suspend functions are wrapped in continuations where the type argument
# is used.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# R8 full mode strips generic signatures from return types if not kept.
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>
-dontwarn org.intellij.lang.annotations.Language
-dontwarn com.android.extensions.xr.**

-keep class com.amplifyframework.** { *; }
-dontwarn software.amazon.awssdk.**
-dontwarn com.amazonaws.mobileconnectors.s3.transferutility.**
-dontwarn com.amazonaws.services.s3.internal.**
-dontwarn com.google.thirdparty.publicsuffix.**
-dontwarn com.twilio.**
-dontwarn com.twilio.audioswitch.**
-dontwarn tvi.webrtc.**

# Play Console's edge-to-edge check scans the release dex for this call. Keep it, and the names in
# its signature, or the call is inlined/renamed out of recognition and the warning stays.
-keep class androidx.activity.EdgeToEdge {
    public static void enable(androidx.activity.ComponentActivity, androidx.activity.SystemBarStyle, androidx.activity.SystemBarStyle);
}
-keepnames class androidx.activity.ComponentActivity
-keepnames class androidx.activity.SystemBarStyle
