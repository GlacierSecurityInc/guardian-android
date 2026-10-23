# Local unit tests

JVM unit tests for the pure-Java logic behind the app's features. They run on the host with no
device, no emulator and no network.

```bash
./gradlew test --offline
```

That runs `testPlaystoreDebugUnitTest`. HTML reports land in
`build/reports/tests/<variant>/index.html`.

To run one class or one method:

```bash
./gradlew testPlaystoreDebugUnitTest --offline --tests '*PasswordValidatorTest'
```

## How it is wired

- `src/test/java` — the source set. Tests compile against the variant under test.
- `build.gradle` declares `junit:junit:4.13.2`, `org.json:json:20250517` and
  `com.google.truth:truth:1.4.4` as `testImplementation`. Both assertion styles are in use --
  some files use Truth, the rest use plain JUnit; either is fine, just stay consistent within a
  file. **org.json must stay declared** even though `android.jar` provides the
  same classes: the android.jar stubs throw on every call, and a `testImplementation`
  dependency lands ahead of the mockable jar on the unit-test classpath.
- `testOptions.unitTests.returnDefaultValues = true`, so an un-stubbed Android call answers
  null/0 instead of throwing "not mocked". A test only has to deal with the Android surface it
  actually touches.
- Mockito (`org.mockito:mockito-core:5.23.0`) and Robolectric (`org.robolectric:robolectric:4.17`)
  are available — see [Three tiers](#three-tiers) for which to reach for. Hand-written fakes still
  live in `com.glaciersecurity.glaciermessenger.testutil`: `FakeSharedPreferences` is a real
  in-memory store, and it is still the right tool for a test that only needs preferences, because
  it costs nothing while Robolectric costs a sandbox boot.

## Three tiers

Prefer the cheapest tier that can express the test. Wall-clock cost per test class is roughly
plain &lt; Mockito &lt;&lt; Robolectric.

| Tier | Use it when | Cost |
| --- | --- | --- |
| Plain JUnit (no runner) | The logic is pure Java, or the only Android calls are ones `returnDefaultValues` can answer with null/0 | free |
| Mockito | You need to *state a precondition* the code reads through an Android object — "this package is installed", "this cursor has two rows" | ~nothing |
| Robolectric (`@RunWith(RobolectricTestRunner.class)`) | You need real framework *behaviour* — `Settings`, resources, `SharedPreferences`, `Looper`, `Uri`, a real `Context` | boots a sandbox |

The distinction between the last two matters more than it looks. Mockito answers "what does the
code do when the world looks like this?" and is the better choice when the world is a fact you
can simply assert (`SystemSecurityInfoHookingTest` stubs `PackageManager.getPackageInfo` to say
LSPosed is installed). Robolectric answers "what does the code do against a real Android?" and is
the only option when the framework's own logic is part of what you are testing
(`SystemSecurityInfoSettingsTest` writes `Settings.Global` and reads it back through a real
`ContentResolver`; against the mockable `android.jar` every read returns the `returnDefaultValues`
zero, so the "enabled" branch is simply unobservable).

Do not reach for Robolectric to avoid writing a fake. `FakeSharedPreferences` exists and is faster.

### Robolectric and coverage

Until 2026-09-22 **a Robolectric test contributed exactly zero coverage** to the JaCoCo report,
silently. Robolectric loads the classes under test through its own sandbox classloader and the
classes it hands back carry no source location, which JaCoCo skips by default. The effect was
not subtle: `GlacierCertificatePinner` has a full Robolectric suite and reported 0 of 31 lines,
and `services` looked like a completely untested 10,000-line package because its only test is
Robolectric.

`build.gradle` now sets `includeNoLocationClasses = true` on every Test task, with
`excludes = ['jdk.internal.*']` alongside it -- that second half is not optional, because the
agent otherwise tries to instrument the JDK's runtime-generated reflection accessors and the
run dies with a NoClassDefFoundError.

The practical consequence when picking a tier: coverage is no longer a reason to prefer plain
JUnit over Robolectric. Speed still is -- a sandbox boot is not free -- but a Robolectric test
now counts for exactly as much as a plain one.

### Robolectric specifics

`src/test/resources/robolectric.properties` sets two defaults for every Robolectric test:

- **`application=android.app.Application`** — the stock Application, *not* the app's
  `GlacierGuardian`. Its `onCreate()` configures Amplify/Cognito, Firebase, the `DatabaseBackend`
  and the WireGuard monitor; under Robolectric that chain dies on conscrypt's missing JVM native
  library, and it is the wrong thing to want in a unit test regardless. A test needing app-level
  state should set it up explicitly.
- **`sdk=37`** — pinned, not inherited from `targetSdkVersion`. Robolectric needs a
  preinstrumented `android-all` jar per SDK level, and `build.gradle` stages exactly one. **These
  two move together**: changing either the pin here or the coordinate in `build.gradle` without
  the other leaves Robolectric asking for a jar that was never staged.

`RobolectricEnvironmentTest` guards all of that. If it fails, fix the build configuration before
believing anything another Robolectric test says.

Three things in `build.gradle` are load-bearing and non-obvious:

- **The offline `android-all` jar.** Robolectric normally downloads its Android runtime with its
  own Maven resolver at *test* time, which ignores Gradle's `--offline` and its dependency cache.
  The `stageRobolectricSdk` task resolves that jar as a normal Gradle dependency and stages it, and
  the test tasks run with `robolectric.offline=true` pointed at the staging directory. This is what
  keeps `./gradlew test --offline` working.
- **`includeAndroidResources = true`**, without which Robolectric has no merged manifest or
  resource table and fails at startup.
- **conscrypt.** The app ships `org.conscrypt:conscrypt-android`, which expects a native library
  out of the APK; Robolectric needs `conscrypt-openjdk-uber`, which carries one for the JVM. Both
  expose the same `org.conscrypt` package, and on the unit-test classpath the Android one wins —
  every Robolectric test then dies with `UnsatisfiedLinkError: no conscrypt_jni`. `build.gradle`
  excludes `conscrypt-android` from the unit-test runtime classpath only; the app and the
  instrumented tier keep it.
- **Java 21.** `robolectric.properties` pins `sdk=37`, and Robolectric refuses to build a
  sandbox for SDK 37 on anything older: *"Android SDK 37 requires Java 21 (have Java 17)"*. The
  plain JUnit and Mockito tests run fine on 17, so this only bites once a Robolectric test is in
  the run -- which makes it look like an unrelated test broke. CI's unit-test job installs 21 for
  this reason while the lint job stays on 17.
- **`--add-opens` JVM args**, because Robolectric runs real framework code that reaches JDK
  internals the module system closed off. Without them the sandbox cannot boot on JDK 17+.

In a fresh checkout the usual gitignored files must be copied in first, or nothing compiles:
`local.properties`, `google-services.json`, and
`src/main/java/com/glaciersecurity/glaciermessenger/EndPointConfig.java`.

## No real data in tests

Test fixtures must never contain a real account, JID, phone number, token, org or hostname --
not even one that looks harmless. Use the ranges reserved for it:

| Kind | Use |
| --- | --- |
| Accounts / JIDs / email | `testuser@example.com`, `alice@example.com` (RFC 2606) |
| US phone numbers | `202-555-0142` — the `XXX-555-0100..0199` block is reserved for fiction |
| UK phone numbers | `+44 20 7946 0142` — Ofcom's reserved drama range |
| IPv4 | `192.0.2.0/24` (TEST-NET-1) |
| IPv6 | `2001:db8::/32` (documentation range) |
| Hostnames | `example.com`, `example.org` |
| Org names | `acme` or similar obvious placeholders |

Tokens and keys must be constructed, never copied: the JWT in `TwilioAccessTokenParserTest`
decodes to `{"sub":"guardian"}` with the literal signature `signature`.

## What is covered

Two tiers, built against two different sources.

### Tier 1 -- app features

| Feature | Test |
| --- | --- |
| Set new password / Change Password / Forgot Password | `utils/PasswordValidatorTest` |
| Configure DNS / Mobile Threat Defense (DNS) | `entities/GlacierOrgEntityTest` |
| Phone numbers, Input number, Add Number | `utils/PhoneUtilsTest` |
| Search messages | `utils/FtsUtilsTest` |
| Message Correction | `entities/EditTest` |
| Message Confirmations | `entities/ReadByMarkerTest` |
| Org Security Hub Data, Updates on Console | `entities/SecurityInfoTest` |
| Incoming call registration (phone never rings) | `utils/VoiceRegistrationStatusTest` |
| Core/VPN profile rename | `entities/CoreProfileTest` |
| Core Connection: connect, disconnect, reconnect after restart | `wireguard/PersistentConnectionPropertiesTest` |
| Import Profiles, VPN routing (near/far) | `wireguard/ConfigParserTest` |
| DNS, Configure DNS (resolution policy) | `utils/ResolverTest` |
| DNS (configured/not configured), transports and ports | `de/gultsch/minidns/DNSServerTest` |
| Create backup / Restore (backup file header) | `utils/BackupFileHeaderTest` |
| Last seen, message timestamps | `utils/DateParserTest` |
| VPN addressing helpers | `utils/IPTest` |
| Make call / Receive call (Twilio access token) | `utils/TwilioAccessTokenParserTest` |
| Chat message escaping | `utils/XmlHelperTest`, `utils/StringUtilsTest` |
| API environment selection (build-time) | `EndpointsTest` |
| Debug vs release network security config | `NetworkSecurityConfigParityTest` |
| Every configured endpoint host is certificate-pinned | `utils/PinnedEndpointCoverageTest` (Robolectric) |
| Security Hub: hooking/root framework detection | `utils/SystemSecurityInfoHookingTest` (Mockito) |
| Security Hub: developer tools, USB and Wi-Fi debugging | `utils/SystemSecurityInfoSettingsTest` (Robolectric) |
| — (guards the Robolectric wiring, not a feature) | `testutil/RobolectricEnvironmentTest` |

### Tier 2 -- the inherited Conversations core

Tier 1 covers what Glacier added. It does not touch the XMPP client this app is a fork of, which
is most of the code and all of what conversations.im advertises: encrypted messaging, file
sharing, group chat, calls. These tests cover the pure-Java part of that surface, seeded from
upstream's own suite at codeberg.org/iNPUTmice/Conversations and from the feature list on
conversations.im.

| Feature the app claims | Test | Seeded from |
| --- | --- | --- |
| Addressing: every stanza's to/from | `xmpp/JidTest` | upstream `eu.siacs.conversations.xmpp.JidTest` |
| Addressing: an address a person typed or scanned | `xmpp/JidTest` (`ofUserInput` cases) | upstream `Jid.ofUserInput` |
| Privacy: addresses must not reach logs | `xmpp/JidTest` (obfuscate cases) | this fork |
| Stanza construction and stanza injection | `xml/ElementTest` | this fork |
| "Easily share files": what becomes a download | `utils/MessageUtilsTest` | this fork |
| Bidirectional-override spoofing in bodies | `utils/MessageUtilsTest` | this fork |
| OMEMO fingerprint display, SASL escaping, TLS suite selection | `utils/CryptoHelperTest` | this fork |
| XEP-0393 message styling | `utils/ImStyleParserTest` | this fork |
| Emoji rendering of incoming bodies | `utils/EmoticonsTest` | upstream `EmoticonsTest` |
| Link detection in incoming bodies | `utils/PatternsTest` | this fork |
| Calls: ICE candidate exchange, DTLS fingerprint and credentials | `xmpp/jingle/stanzas/IceUdpTransportInfoTest` | this fork |
| Calls: stream direction, description and transport dispatch | `xmpp/jingle/stanzas/ContentTest` | this fork |
| Calls: BUNDLE grouping of streams onto one transport | `xmpp/jingle/stanzas/GroupTest` | this fork |
| Calls: OMEMO-verified transport must carry no plaintext fingerprint | `xmpp/jingle/stanzas/OmemoVerifiedIceUdpTransportInfoTest` | this fork |
| Calls: XEP-0353 propose/proceed parsing | `xmpp/jingle/stanzas/JingleMessageStanzasTest` | this fork |
| File transfer: SOCKS5 and in-band bytestream transports | `xmpp/jingle/stanzas/ByteStreamTransportInfoTest` | this fork |
| Calls: WebRTC data-channel transport and its nested ICE transport | `xmpp/jingle/stanzas/WebRTCDataChannelTransportInfoTest` | this fork |
| Calls: SDP parsing, and the session/media boundary | `xmpp/jingle/SessionDescriptionTest` (Robolectric) | this fork |
| Calls: codec negotiation, XML and SDP round trip | `xmpp/jingle/stanzas/RtpDescriptionTest` (Robolectric) | this fork |
| Group chat: filling in a form from a Bundle | `xmpp/forms/DataBundleTest` (Robolectric) | this fork |
| File transfer: what a downloaded file is typed as | `utils/MimeUtilsTest` | this fork |
| Message display: when two messages merge into one bubble | `entities/MessageMergeTest` (Robolectric) | this fork |
| Calls: why a session ended, in both directions | `xmpp/jingle/stanzas/ReasonTest` | this fork |
| Calls: SDP generated from a peer's Jingle stanza | `xmpp/jingle/SessionDescriptionGuardsTest` | this fork |
| Group chat: room configuration and ad-hoc command forms | `xmpp/forms/FieldTest`, `xmpp/forms/DataTest` | this fork |
| TLS: chaining a bundled CA in front of the system trust store | `crypto/CombiningTrustManagerTest` | this fork |
| Stanza timestamps: delay elements, and error-stanza text | `parser/AbstractParserTest` | this fork |
| Glacier mobile API: polymorphic JSON | `http/mobile/MobileApiResponsesTest` | this fork |
| Calls: XEP-0353 signalling envelope (carbon copy, archive hint) | `generator/MessageGeneratorTest` | this fork |
| TLS: does this certificate belong to the domain we dialled | `crypto/XmppDomainVerifierTest` | this fork |
| TLS: trusting a bundled CA and nothing else | `crypto/BundledTrustManagerTest` | this fork |

The Jingle rows above cover the *stanza* layer, the two SDP guards, and -- on the Robolectric
tier -- `SessionDescription.parse` and `RtpDescription`. The last two are marked as Robolectric
for a reason worth knowing before moving one back: they reach `android.util.Pair`, whose
constructor under the mockable android.jar stores nothing, so a plain-JUnit version of these
tests would pass while asserting that null equals null. (`SessionDescriptionGuardsTest` stays
plain JUnit because it drives the guards directly as the public statics they are, without
touching Pair.) `DataBundleTest` is split from `DataTest` on the same grounds: a stubbed `Bundle`
answers null to `keySet` and false to `containsKey`.

What is still uncovered in `xmpp/jingle/` is the **session state machine** -- `JingleRtpConnection`
and `JingleConnectionManager` -- which needs an `XmppConnectionService`, and
`SessionDescription.of`, which needs a fully populated content map rather than a string. Two
branches of `Reason.of(RuntimeException)` are unreachable from the stanzas package for a different
reason: the `AbstractContentMap` unsupported-transport and unsupported-application exceptions have
package-private constructors, so only a test in their own package can raise them.

Upstream's other eleven test classes were **not** ported, because the code they test does not
exist on this line: `MiniUriTest`, `PatternTest` (upstream's `de.gultsch.common` rewrite),
`ScanResultProcessorTest`, and the eight `im.conversations.android.xmpp` classes --
`DataFormTest`, `DateAndTimeProfilesTest`, `EmojiReactionsTest`, `EntityTimeTest`,
`LastUserInteractionTest`, `StreamElementWriterTest`, `XhtmlImTest` and `EntityCapabilitiesTest`.
Only `EntityCapabilities` has a counterpart here, and it is a partial copy without the model
classes the upstream test drives it through.

### Tier 3 -- the UI and service layer

`ui/` and `services/` are the two largest packages in the module and were the two least covered:
`ui/` itself sat at **exactly 0%** on both this tier and the instrumented one, `services/` at 0.8%.
Most of both is genuinely out of reach from this tier -- an Activity needs an instrumented test --
but the decisions those classes make are not all view code. These tests take
the parts that are ordinary Java or a thin layer over one framework type, and they were chosen on
one criterion: the wrong answer is invisible. None of these failures produce an error; they
produce a screen that is quietly wrong.

| What goes wrong if it breaks | Test |
| --- | --- |
| A received `photo.jpg.pgp` is typed as PGP ciphertext instead of a JPEG | `services/AbstractConnectionManagerExtensionTest` |
| Catch-up negotiates the wrong MAM namespace, or drops archived messages | `services/MessageArchiveVersionTest` |
| A device on Core reads as offline and every gated request is silently skipped | `services/ConnectivityReceiverTest` (Robolectric) |
| Being addressed in a group chat does not notify, or every message does | `services/NickHighlightPatternTest` |
| The call screen shows the caller their own number as the other party | `services/CallInfoTest` |
| A glacier-users response without `xmpp_token` clears the JWT and reconnects expecting a password that does not exist | `services/XmppTokenCredentialsTest` (Mockito) |
| A `.3gp` voice message opens a black video surface instead of an audio player | `ui/util/AttachmentTest` (Robolectric) |
| Text is restyled as someone else's quotation -- `>5 people came` | `ui/util/QuoteHelperTest` |
| A stale playback-speed index throws from inside a list bind, or shows the wrong speed | `ui/util/PlaybackSpeedOptionsTest` |
| A shared location's thumbnail never loads in a comma-decimal locale, or pins the wrong hemisphere | `ui/util/LocationBubbleFormattingTest` |
| A shared location parses to the wrong point, or the map jumps to a worse fix | `ui/util/LocationHelperTest` (Robolectric) |
| A direct call is addressed to a full JID with an empty resource, which nothing answers | `ui/util/PresenceSelectorTest` |
| The voicemail list shows wrong durations, or is in the wrong order | `ui/adapter/VoicemailRecordTest` |

Three of these pin **observed behaviour that is wrong**, so the test is a description rather than
an endorsement, and a fix should change the test:

- `VoicemailRecordTest` -- `compareTo` compares the *formatted* `MM/dd/yyyy hh:mm a` string, so the
  list sorts wrongly across midday (09:00 AM after 05:00 PM) and across any month or year boundary.
  The duration format is `mm:ss`, so a voicemail over an hour loses the hours silently.
- `CallInfoTest` -- a payload with no `direction` makes both accessors throw rather than defaulting.
- `LocationHelperTest` -- `parseLatLong` indexes `parts[1]` without checking the split produced two
  parts, so a malformed geo URI throws `ArrayIndexOutOfBoundsException`, not the documented
  `NumberFormatException`.

Every class here was mutation-checked: the method under test was broken, the suite was confirmed to
go red on exactly the expected cases, and the change reverted. That is worth doing rather than
assuming, because `returnDefaultValues` makes a vacuous pass look identical to a real one.

What this tier still cannot reach is the top-level `ui/` package itself -- 19,757 lines of
Activities, at 0% here and at 0% on `src/androidTest` too. Espresso and uiautomator are declared in
`build.gradle` and entirely unused. That is the next piece of work, and the way in is the
`src/debug` activity pattern (`WindowFlagsProbeActivity`): an activity declared in `androidTest`
cannot be launched by the instrumentation's target process.

### The JID normalisation gap, and the fix

Writing this tier turned up one difference from upstream worth fixing rather than documenting.
`Jid.of` / `Jid.ofEscaped` apply nameprep and nothing else, and nameprep (RFC 3491) prohibits
neither ASCII space nor ASCII control characters -- excluding those is IDNA's job, via the LDH
rule. Nameprep also does not fold punycode, so `xn--kxae4bafwg.xn--pxaix.gr` and the same domain
spelled in Unicode produced two `Jid` values that were not equal: a contact saved under one
spelling did not match a stanza arriving under the other.

Upstream solves this with `Jid.ofUserInput`, which validates the domain as a hostname and runs
`java.net.IDN` over it. That method is now ported here (minus upstream's Quicksy-only rule about
gmail addresses on the Play Store flavour, which has no counterpart on this line), and the places
where a person supplies an address go through it: `EnterJidDialog`, `StartConversationActivity`
(contact validation, prefill, and joining a conference), `CreatePublicChannelDialog`,
`UpSettingsFragment`, and `XmppUri` (invite links and scanned QR codes).

**Order matters: convert to ASCII first, then validate.** Upstream's version applies a
`\p{L}\p{N}`-based hostname rule to the *Unicode* domain, and a vowel sign is `\p{M}`, not
`\p{L}` -- so that rule rejects Devanagari, Thai, Arabic and Hebrew domains while leaving Greek
and Armenian working. The breakage is invisible unless you happen to test the right language.
This port runs `IDN.toASCII` under STD3 rules first and applies the LDH rule to the punycode
form, where every script is ASCII. STD3 is also what rejects a space, a control character, an
underscore, an empty label and a leading or trailing hyphen; the pattern is left to enforce the
253-character limit, which `toASCII` does not check for the name as a whole.
`JidTest.ofUserInputAcceptsScriptsWrittenWithCombiningMarks` is the regression guard.

**`Jid.of` deliberately stays lenient.** An address that arrived on the wire is already
normalised, and re-deriving it on the stanza path would risk changing what an incoming message
routes to. `JidTest.ofStaysLenientBecauseItParsesStanzasNotUserInput` pins that split so nobody
"fixes" it later by tightening the wrong factory. The account address at
`LoginAccountActivity` is also left alone -- it comes from the Glacier backend, not from a text
field.

**Two tests in this tier are characterisation, not requirements.** They pin behaviour that
differs from upstream so the difference is visible and a future fix breaks the test rather than
passing silently. Each says so in a comment, and each names what would make it flip:

- `EmoticonsTest.codePointsOutsideThisForksRangesAreNotRecognised` -- this copy of `Emoticons`
  predates upstream's Unicode range updates and misses the Arrows and Miscellaneous Symbols and
  Arrows blocks.
- `MessageUtilsTest.anUppercaseSchemeIsNotDownloadable` -- `treatAsDownloadable` compares the
  protocol with `equalsIgnoreCase`, but never reaches that line, because the
  `URL.WELL_KNOWN_SCHEMES` gate above it is a case-sensitive list membership test. It fails
  closed, so it is a papercut rather than a hole.

`JidTest` calls `LibIdnXmppStringprep.setup()` in a `@BeforeClass`. **This is load-bearing.**
jxmpp does no stringprep at all until an implementation registers itself, and in the app that
happens in `XmppConnectionService.onCreate`, which a unit test never runs. Without the call the
validation cases pass vacuously. It is also worth knowing for its own sake: any JID parsed
before the service starts gets no stringprep.

## Coverage

Coverage is **measured, not enforced**. There is no threshold and nothing fails on the number:

```bash
./gradlew jacocoPlaystoreDebugReport -PcoverageReport
```

The report task depends on the matching test task, so that one command runs the tests and
writes `build/reports/jacoco/jacocoPlaystoreDebugReport/` (HTML to read, XML for tooling).

`-PcoverageReport` is not optional and not a convenience. The `jacoco` plugin attaches its
agent to every `Test` task as soon as it is applied, and resolving that agent needs the network
on a cold cache -- which would break `./gradlew test --offline` for anyone who had never run
coverage. So the agent is off unless asked for, and the default test run is unchanged. Running
a report task without the flag fails with a message telling you to add it, rather than quietly
handing you the previous run's numbers.

**Read the global percentage as a denominator, not a score.** It is around **2.6%**, and that is
the expected answer rather than a problem to fix: the denominator is the whole module, ~74,000
lines of which the great majority is `ui/` and the inherited Conversations core that this tier
does not try to reach. What the per-package view is actually useful for is the shape:

| Package | Line coverage |
| --- | --- |
| `http/fake/` | ~74% |
| `wireguard/` | ~61% |
| `xml/` | ~38% |
| `utils/` | ~12% |
| `entities/` | ~5% |
| `parser/`, `generator/`, `crypto/sasl/`, `xmpp/jingle/stanzas/` | **0%** — 4,200 lines, no test touches them |

The top and bottom rows are the same point from two directions: `http/fake/` was written with its
test, and the four at zero are inherited code nobody has come back to.

A global threshold over that would be gamed or ignored. If a gate goes in later it should be on
the lines a change *touches*, which is a question the XML report can answer.

## The fake Glacier backend

Neither tier can reach the Glacier console, and some of what the app does with it -- the phone
module's add/remove flows, the org sync, the Security Hub upload -- is only interesting against a
backend that answers. `-PglacierFakeBackend=true` builds a debug APK that answers the whole mobile
API from memory, with no console, no login and no Twilio, and lets any endpoint be switched to a
failure scenario over adb. See
[`src/main/java/com/glaciersecurity/glaciermessenger/http/fake/README.md`](../main/java/com/glaciersecurity/glaciermessenger/http/fake/README.md).

`FakeGlacierBackendTest` in this tier pins the fake's responses against the app's own DTOs, so the
two cannot drift apart unnoticed. It runs offline like everything else here -- it drives the
responder directly and does not need the Gradle flag.

## Instrumented tests (`src/androidTest`)

The instrumented tier is 10 classes and needs a device or emulator; see
[`src/androidTest/README.md`](../androidTest/README.md) for the fleet, the orchestrator and
overnight runs. It is not part of `./gradlew test`.

```bash
ANDROID_SERIAL=emulator-5562 ./gradlew connectedPlaystoreDebugAndroidTest --offline
```

**CI compiles that tier but does not run it.** The `unit-tests` job assembles the test APK
(`assemblePlaystoreDebugAndroidTest`), which type-checks every instrumented test against the app
it tests without an emulator. That guards against the tier rotting -- a rename in `src/main`
breaking a test nobody runs between fleet passes -- and it is the only automated signal those
10 classes get. Running them is still a deliberate act.

That job -- like the lint job -- is skipped for a pull request from a **fork**, which cannot be
given the repository secrets the build needs. Nothing in this repo's history is a fork PR, but do
not read a green check on one as a check having run. Dependabot PRs *are* covered: they branch
inside this repo, so the guard passes, and the three build secrets exist as repository Dependabot
secrets.

## What these tests cannot cover

Most app behaviour is device behaviour and stays a manual pass:

- Anything with a screen: login, chat, group config, settings, contact details, the dialer.
- Calls (G2G, Twilio, Jingle), notifications, foreground services, ringtones, audio routing.
- VPN/core connection, DNS resolution, signal and connectivity states, container switching.
- Backup/restore, S3 upload, profile import, permissions, biometrics, app lock.
- The DNS lookup itself: `ResolverTest` covers port policy, hostname validation, candidate
  ordering and the see-other-host parser, but anything that actually queries a server needs a
  network.
- The rest of the inherited Conversations core that is reachable only through an Android type:
  `utils/XmppUri` (invite links and the OMEMO fingerprints carried in a scanned QR code) goes
  through `android.net.Uri`, and `utils/MimeUtils`, `utils/UIHelper`, `utils/StylingHelper` and
  `utils/IrregularUnicodeDetector` need `Context`, resources or `Spannable`. Robolectric is
  wired in, so these are now reachable and simply not written yet. `XmppUri` is
  the one worth doing first -- it parses untrusted input from a scanned QR code and decides
  which key the app trusts.
- The Security Hub **version comparisons** (latest OS, latest Glacier app). The `ComparableVersion`
  logic is inlined inside `isLatestOS()` / `isLatestGlacier()`, which are instance methods on a
  `SystemSecurityInfo` built from an `XmppConnectionService`, and duplicated again in
  `SecurityHubWorker`. Neither Mockito nor Robolectric helps while the logic lives there — it needs
  extracting into something callable first. `SecurityInfoTest` covers the payload those checks
  produce, not the checks.

  The rest of the Security Hub checks *are* now reachable, and the two new test classes above cover
  the parts that can be stated honestly: the installed-package scan and the
  `Settings.Global`-backed debugging flags. Still out of reach are the checks that interrogate the
  real machine rather than the framework — `isRooted` shells out to `su` and stats filesystem
  paths, `isFridaPortOpen` opens a TCP socket, `isHookingFrameworkInStack` and
  `isFridaAgentInMemory` inspect the live process, and `isRoDebuggableEnabled` execs `getprop`.
  Robolectric does not fake any of those, so on the JVM they report whatever the *host* looks like.
  Note this means `isHookingPackageInstalled`'s `XposedBridge` classpath probe is untestable too,
  which is why its test pins the package-scan branch only.
