# Instrumented (on-device) tests

Tests that run **inside the app's own process on a real Android device or emulator**. The JVM
tier in [`src/test`](../test/README.md) covers everything that can be decided without one; this
tier exists for the things that cannot.

```bash
# one emulator you started yourself -- ANDROID_SERIAL is not optional, see "Sharing devices"
ANDROID_SERIAL=emulator-5562 ./gradlew connectedPlaystoreDebugAndroidTest

# emulators the build creates, boots and throws away
./gradlew api34AtdPlaystoreDebugAndroidTest       # one device
./gradlew fleetGroupPlaystoreDebugAndroidTest     # API 34 + API 36
./gradlew matrixGroupPlaystoreDebugAndroidTest    # + minSdk 30, API 34 w/ Google APIs, tablet
```

HTML reports land in `build/reports/androidTests/`, JUnit XML in
`build/outputs/androidTest-results/`.

**CI compiles this tier, and runs none of it.** The `unit-tests` job in
`.github/workflows/code-quality-scan.yml` assembles the test APK:

```bash
./gradlew assemblePlaystoreDebugAndroidTest
```

That type-checks every test here against the app it tests, on a runner, with no emulator. It is
the only automated signal these tests get, and it exists because the gap between fleet runs is
long enough for a rename in `src/main` to break a test and go unnoticed. It says nothing about
whether any test passes — that is still a deliberate act, and before a release it should be
repeated fleet runs.

**On every push, but not on a pull request from a fork.** Every job in that workflow is guarded
by `github.event.pull_request.head.repo.full_name == github.repository`, so a fork PR gets no
lint, no unit tests and no compile check for this tier. That is not a gap that can be closed by
moving the step: nothing here compiles without `MAPBOX_DOWNLOADS_TOKEN` (dependency resolution),
`google-services.json` and `EndPointConfig.java`, and GitHub does not give a fork PR access to
repository secrets, so the job would fail rather than report. In practice every pull request on
this repo is opened from a branch in the repo itself, where the guard passes; treat a fork PR as
unverified and re-run it from an internal branch.

**Dependabot PRs are covered.** They branch inside this repo, so the guard passes, and the three
build secrets are configured as repository *Dependabot* secrets as well as Actions secrets. The
comments on the guard still say "Fork/Dependabot" -- that predates those secrets existing.

## What belongs here

Put a test here only if it needs something a JVM cannot provide. Everything else runs faster and
more reliably in `src/test`, including under Robolectric. The current suite is here because of:

| Area | Why it cannot run off a device |
| --- | --- |
| `KeystoreCredentialStoreTest` | There is no `AndroidKeyStore` provider on the JVM. Every call fails before it reaches an assertion. |
| `AxolotlStorePersistenceTest` | libsignal needs `libsignal_jni.so`, and the build deliberately strips the desktop natives out of the APK. |
| `DatabaseSchemaTest`, `ConversationPersistenceTest` | The FTS4 external-content index and its triggers behave according to the SQLite that ships with Android, which is not the one Robolectric ships. |
| `DatabaseMigrationTest` | `onUpgrade` rebuilds tables; the failure mode is a silently emptied table, not an exception. |
| `DatabaseDowngradeGuardTest` | Reads `user_version` out of a real database file, and depends on how Android's SQLite reacts to a file that is not a database. |
| `OrientationLockTest` | The correct answer differs per device — `R.bool.portrait_only` is `false` only in `values-sw600dp`. |
| `IncomingCallChannelTest` | Depends on the real `NotificationManager` refusing to change a channel's sound after creation. |
| `ScreenshotSettingTest` | `FLAG_SECURE` is a window-manager property. |
| `ConversationsFileObserverLiveTest` | There is no inotify behind `FileObserver` off a device. The JVM tier drives the dispatch by hand, so only this one proves a watch the list claims to hold actually delivers. |
| `ui/journey/*` | They enter real activities. A JVM has no window manager, no `BIND_AUTO_CREATE`, and no soft keyboard — and Robolectric's shadow of all three is not what ships. See [Journey tests](#journey-tests). |
| `GlacierApiContractTest`, `DestructiveWriteGuardLiveTest` | They need a signed-in session and the app's own OkHttp client. Both **skip by default**. |

## The UI tier

`ui/` is the largest package in the module and sat at exactly 0% on both tiers until these four
classes. Most of it still does: every screen past sign-in needs an account, and the orchestrator
clears app data before each test here, so a signed-out device is the only state a test in this
suite starts from. What that state *can* reach is worth more than it sounds:

| Class | What it covers | Why it needs a device |
| --- | --- | --- |
| `ui/widget/CustomViewRenderingTest` | Every custom view: measured, laid out and drawn onto a real bitmap | `Canvas`, `Paint`, gradients and text metrics are native. Against the mockable android.jar every draw is a no-op, so the assertions would hold against a view that drew nothing. |
| `ui/LayoutInflationTest` | All ~143 layouts inflate under the app's theme | Robolectric's resource loader is not the platform's, so an inflation it accepts is not evidence the device would. |
| `ui/adapter/SecurityHubListAdapterTest` | The Security Hub list, bound through a real `RecyclerView` | RecyclerView measures, lays out and recycles for real; the bind body reads `getAdapterPosition()`, which is `NO_POSITION` for a holder no RecyclerView owns. |
| `ui/LaunchRoutingTest`, `ui/LoginScreenTest` | The launcher trampoline's destination, and the sign-in screen through Espresso | They are activities. |

Four things worth knowing before adding to this tier:

- **Draw assertions are about difference, not pixels.** The fleet spans densities and font
  renderers, so a golden image would be noise. "The connected orb does not look like the
  disconnected one" is the claim that actually matters and it survives a re-render.
- **Everything runs on the main thread.** A detached view can be measured off it, but
  `invalidate()` and `ValueAnimator` cannot, and those are what the state setters do.
  `testutil/ViewHarness.onMain` runs the body and re-throws on the calling thread, so a failed
  assertion inside is a test failure rather than a process crash.
- **A trampoline never reaches RESUMED**, so `ActivityScenario.launch` waits forever on one. Use
  an `Instrumentation.ActivityMonitor` and assert on the activity that comes up next -- which is
  the actual contract anyway.
- **Assert `finish()` on a route that does not clear the task.** `ConversationActivity` sends a
  device with no accounts to `StepperWizard` with `FLAG_ACTIVITY_CLEAR_TASK`, which destroys the
  trampoline whether or not it ever called `finish()`; only the returning-account route
  (`ConversationsActivity`, no flags) makes that assertion mean anything.
- **Seed accounts disabled unless the test is about connecting.** An enabled account makes
  `XmppActivity.onStart` bind `XmppConnectionService`, which reconnects eligible accounts and puts
  a real DNS lookup and socket attempt for the fixture domain on the wire. `OPTION_DISABLED` keeps
  the routing condition and leaves the network alone.
- **Layouts are inflated into a real parent with `attachToRoot` true**, so a `<merge>` root is
  covered rather than throwing.

Measured on `api34Atd`, `playstore`, instrumented tier only, before and after these five classes
(117 tests -> 163):

| Package | before | after |
| --- | --- | --- |
| `ui` | 0 / 19,754 — **0.0%** | 639 / 19,754 — 3.2% |
| `ui/widget` | 0 / 872 — **0.0%** | 458 / 874 — **52.4%** |
| `ui/util` | 16 / 1,622 — 1.0% | 110 / 1,622 — 6.8% |
| `ui/adapter` | 0 / 3,496 — **0.0%** | 98 / 3,496 — 2.8% |
| `services` | 55 / 10,321 — 0.5% | 1,023 / 10,321 — 9.9% |
| whole module | 1,683 / 73,450 — 2.29% | 4,518 / 73,452 — 6.15% |
| packages with zero covered lines | 91 | 81 |

`services` moves the most in absolute terms and none of it is a service test: launching an activity
binds `XmppConnectionService`, so the two activity classes drag most of that in behind them. It is
real coverage of real startup, but it is a side effect -- do not read it as the service layer being
tested.

`LoginScreenTest` signs nothing in. No credentials are typed, no network call is made and no
account is created. Driving a real sign-in needs a real account and belongs in a manual pass,
outside the repository.

## Tests that need a signed-in device

`SignedInScreensTest` is the only class here that reaches a screen past the sign-in form, and it
exists because nothing else can: the orchestrator clears package data before every test, so every
other test in this tier starts signed out, and there is no way to sign in from inside one.
Cognito/Amplify auth does not go through OkHttp, so `-PglacierFakeBackend=true` does not help —
that flag works precisely by ensuring *no Cognito session is fetched*.

So the session is arranged out of band and the test borrows it. It skips unless you opt in, so ordinary runs, managed-device runs and
fleet runs are all unaffected (verified: 5 tests, 5 `<skipped/>`, 0 failures without the
flag).

```bash
export ANDROID_SERIAL=emulator-5562

# 1. install, then sign in on the device.
./gradlew installPlaystoreDebug

# 2. run. clearPackageData=false is not optional -- it is what the session survives.
./gradlew connectedPlaystoreDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.glacierSignedInUi=true \
  -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false \
  -Pandroid.testInstrumentationRunnerArguments.class=com.glaciersecurity.glaciermessenger.ui.SignedInScreensTest
```

**`connectedAndroidTest` uninstalls the app when it finishes**, which takes the account with it —
measured, not assumed: after a run the package is simply gone and the next launch reports
`Activity class ... does not exist`. `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`
is what makes signing in once and iterating possible; without it every run costs a reinstall and a
fresh sign-in.

**No credentials appear in the class, and none may.** It asserts only structure — a view exists,
a list is populated, the activity is locked to portrait — never user content. An assertion message
ends up in the run log, and the account address is exactly what must not be there. Credentials
stay outside the repository.

**A resource-id rename is not a mutation.** Worth knowing before mutation-checking anything on this
tier: aapt assigns ids by position, so renaming `@+id/security_hub_nav` leaves the same numeric id
and the menu item is still there — the test correctly passed and it looked like a weak test for an
hour. Mutate behaviour in Java (here, `menu.removeItem(...)` at runtime), not names in resources.

### Gate these in `@Before`, never `@BeforeClass` or `@ClassRule`

This is the part that bites. JUnit reports an `Assume` failure against the description it was
thrown from, and a class-level hook has a class-level description: no `testStarted` is ever
fired, so **the runner produces no result at all for those methods**. AGP enumerates the suite
first and then counts what comes back, so a class skipped that way shows up as a shortfall and
fails the whole run even though nothing is broken:

```
Test run failed to complete. Expected 89 tests, received 77     # BUILD FAILED, 0 failures
```

Thrown from a `@Before`, the same assumption is reported against each test method, which fires
`testStarted`/`testFinished` around it and lands in the XML as a proper `<skipped>`. Measured on
`api34Atd`: `tests="9" failures="0" skipped="9"` instead of the class being absent entirely.

The XML's `<skipped/>` is bare — it carries no message, and the HTML report does not show one
either. The assumption text ("opt in with ...", "this build was not assembled with ...") lands in
the per-test `logcat-<class>-<method>.txt` next to the XML, which exists only because the test now
starts at all. That file is where to look when you expected a class to run and it did not.

A `@ClassRule` does **not** solve this — it is reported against the class description exactly
like `@BeforeClass`. If you add another class that gates itself, put the assumption in `@Before`
and keep any expensive setup below the gate, so a run that is only going to skip touches nothing.

## The emulator fleet

`android.testOptions.managedDevices` in `build.gradle` defines four devices. Gradle creates them
under `~/.android/avd/gradle-managed`, boots them headless, runs the suite and tears them down.

| Device | API | Image | Why |
| --- | --- | --- | --- |
| `api30` | 30 | aosp-atd | `minSdkVersion`. Nothing else catches an API call added after 30 that is reached without a version guard. **Downloads its system image on first use.** |
| `api34Atd` | 34 | aosp-atd | API 34 is a branch point in this code: `services/CallIntegration.java` has a second Telecom audio-routing implementation behind `UPSIDE_DOWN_CAKE`. ATD images have no UI shell, so they boot fastest and never interrupt a run with a system dialog. |
| `api34Google` | 34 | google | Same API level with Google APIs present, for anything that resolves a Play Services class. |
| `api36` | 36 | google_apis_playstore | Current generation. `targetSdkVersion` is 37, but the only API 37 image installed locally is the 16 KB-page playstore variant, which the managed-device resolver does not address by name — the physical fleet covers 37. |
| `tablet34` | 34 | aosp-atd | The only profile wide enough to resolve `values-sw600dp`. Every other device here is a phone, so without it the tablet half of the app is untested — `OrientationLock` deliberately does *not* lock tablets, and a change that locked them would pass on all four phone profiles. |

AGP names the AVD after the image rather than the block, so `api34Atd` becomes
`~/.android/avd/gradle-managed/dev34_aosp_atd_arm64-v8a_Pixel_6`. **Another checkout that defines
the same API level, image and device profile shares that AVD**, and AGP serialises access with
the `.lock` files and the `MDLockCount` in `active_gradle_devices` next to it — a second build
waits rather than colliding. If a run sits doing nothing at the start, that is what it is waiting
for; `android.experimental.testOptions.managedDevices.deviceLockTimeoutMinutes` bounds the wait.

Groups: `fleet` (api34Atd + api36, both locally installed) is the everyday target; `matrix` is
all five and **the only group that covers the `sw600dp` branch** — put anything that asserts
tablet behaviour behind a `matrix` run, not a `fleet` one.

**`--rerun` binds to the task it follows, so it does not reach a group's devices.** It is a task
option, not a build flag: Gradle attaches it to the task name immediately before it on the command
line. `fleetGroupPlaystoreDebugAndroidTest --rerun` therefore reruns only the group task, and its
member `api34Atd…`/`api36…` tasks still go through ordinary up-to-date checks.

Naming both device tasks is **not** enough for the same reason —
`./gradlew api34Atd… api36… --rerun` applies `--rerun` to `api36…` alone. Measured 2026-09-23: that
invocation ran api36 and reported `api34AtdPlaystoreDebugAndroidTest UP-TO-DATE` in the same build,
reusing a results XML written half an hour earlier, under one `BUILD SUCCESSFUL`. Repeat the flag
after **every** task:

```bash
./gradlew api34AtdPlaystoreDebugAndroidTest --rerun \
          api36PlaystoreDebugAndroidTest --rerun \
  -Pandroid.testInstrumentationRunnerArguments.package=…
```

This is the single-device UP-TO-DATE trap again and worse on a group, because a device that never
ran is indistinguishable in the summary from one that passed. Check the per-device XML mtimes under
`build/outputs/androidTest-results/managedDevice/debug/flavors/<flavour>/<device>/` against the
source you just changed before believing any multi-device result.

A group **boots** its devices concurrently — `gradle.properties` caps that at two, raise
`android.experimental.testOptions.managedDevices.maxConcurrentDevices` on a dedicated runner —
but then runs the suite on them **one device after another**, because they are ordinary tasks in
a single Gradle project. Expect roughly 2 min for one device and a little over 4 min for the
two-device `fleet`. Budget a group run at roughly devices × single-device time.

To cut that down, `-Pandroid.experimental.androidTest.numManagedDeviceShards=2` splits the suite
across two copies of each device, at the cost of twice the emulators; it is worth measuring
before relying on it, since the shards still compete for `maxConcurrentDevices`.

## Every test runs in its own process

`testOptions.execution = 'ANDROIDX_TEST_ORCHESTRATOR'`, with
`clearPackageData: 'true'` in the runner arguments. Each test method gets a fresh process and a
freshly cleared app data directory, so:

- **Nothing carries over.** A test must seed whatever state it needs. `DatabaseBackend.getInstance`
  hands out a database that is empty at the start of every test.
- **Process-wide statics are fresh** — but `GlacierGuardian.onCreate` still runs first, so a
  static it latches (`DatabaseBackend`'s downgrade check, for instance) is already set by the time
  a test starts. `DatabaseDowngradeGuardTest` resets those by reflection and explains why.
- **A native crash costs one test**, not the rest of the run.
- It costs roughly a second per test. For a fast single-class loop, override it:
  `./gradlew connectedPlaystoreDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…`
  still uses the orchestrator; drop `execution` in a local edit if you need the speed.

Two things survive `pm clear`, because the system owns them rather than the app: **Android
Keystore entries** and **notification channels**. Tests that create either clean up after
themselves — see the `@After` in `KeystoreCredentialStoreTest` and `IncomingCallChannelTest`.

**A coverage run turns `clearPackageData` off**, because the JaCoCo agent writes into the
directory it clears — see [Coverage](#coverage). Every test here therefore has to seed and clean
its own on-disk state rather than leaning on the clear, and the suite is verified both ways. The
fresh *process* per method is not affected either way.

## Coverage

Both tiers are measured, separately and together. Nothing is gated on the numbers — the JaCoCo
block in `build.gradle` explains why a global line percentage would be a bad gate on this module.

```bash
# instrumented tier on one device, then its report -- about 2 min 30 s all in
./gradlew api34AtdPlaystoreDebugAndroidTest --rerun -PcoverageReport
./gradlew jacocoPlaystoreDebugAndroidTestReport -PcoverageReport

# both tiers in one number
./gradlew testPlaystoreDebugUnitTest -PcoverageReport
./gradlew jacocoPlaystoreDebugCombinedReport -PcoverageReport
```

| Task | Reads | Answers |
| --- | --- | --- |
| `jacoco<Flavour>DebugReport` | `test<Flavour>DebugUnitTest` | what `src/test` reaches |
| `jacoco<Flavour>DebugAndroidTestReport` | the `.ec` files of whichever devices have run | what `src/androidTest` reaches |
| `jacoco<Flavour>DebugCombinedReport` | both | what is covered at all |

XML and HTML land in `build/reports/jacoco/<task>/`.

### `-PcoverageReport` turns `clearPackageData` off

That is the part worth knowing, and it is why this took a measurement rather than a one-line
commit. The flag does two things: it sets `enableAndroidTestCoverage` on the `debug` build type,
and it flips `clearPackageData` to `false` for the run.

JaCoCo on Android is **offline instrumentation** — there is no JVM agent, so the app's classes
are rewritten at build time and the running app writes its `.ec` file into its own data
directory. That is exactly what the orchestrator's `pm clear` removes between tests. With both
settings on, a **fully passing** run produces **zero `.ec` files**: the intermediates directory
is created and stays empty, and the report reads 0%. Measured on `api34Atd`, 2026-09-22 — with
`clearPackageData=false` the same suite produced one `.ec` file per test.

**What that gives up is narrower than it looks.** `testOptions.execution` is unchanged, so every
test method still gets its own process — process-wide statics are still fresh and a native crash
still costs one test. Only on-disk state carries over. Every test in this tier seeds and cleans
its own, so the suite passes either way; `ConversationPersistenceTest` is the one that had to be
taught to, since `messages_index` is global and its search assertions were reading rows left by
earlier tests. It now tags each message with a per-test marker and ANDs that into every query.

**If you turn the flag on by hand, turn `clearPackageData` off too**, or you will get a green run
and an empty report.

### The report is scoped to whatever ran

AGP offers its own `createManagedDevice<Flavour>DebugAndroidTestCoverageReport`, and it is not
usable here: asking for it with only `api34Atd` still triggers `api36Setup`, `api34GoogleSetup`,
`api30Setup` and `tablet34`, so it needs every device in the `managedDevices` block to have run
and every system image to be installed. `jacoco<Flavour>DebugAndroidTestReport` instead globs
`**/*.ec` out of

- `build/intermediates/managed_device_code_coverage/<flavour>DebugAndroidTest/` — one directory
  per managed device, and
- `build/outputs/code_coverage/<flavour>DebugAndroidTest/` — `connectedAndroidTest`,

so one device, a `fleet` group or a phone over adb all produce a report, and it covers exactly
what ran. It deliberately does **not** depend on any device task — which devices to run is your
decision — so run the device task first and the report second. It fails with a message rather
than an empty report if there is no data.

### A crashed test costs coverage for everything after it

Seen once while measuring this: `AxolotlStorePersistenceTest` took the instrumentation process
down with it, the run reported `1 failed`, and **coverage collection stopped there** — 23 `.ec`
files instead of 111, with the missing ones being every class ordered after the crash. The run
that followed was green and produced all 111. So a coverage number from a run with a failure in
it is not just missing that test, it is missing everything behind it. Check the `.ec` count
against the test count before reading the report:

```bash
find build/intermediates/managed_device_code_coverage -name '*.ec' | wc -l
```

### What the instrumented tier covers that the JVM tier does not

Measured on `api34Atd`, `playstore`, 2026-09-22 — 111 instrumented tests (13 skipped) against
509 JVM tests:

| Tier | Lines |
| --- | --- |
| JVM (`src/test`) | 2,260 / 74,283 — 3.0% |
| Instrumented (`src/androidTest`) | 1,652 / 74,283 — 2.2% |
| Combined | 3,627 / 74,283 — 4.9% |

The two overlap by only 285 lines, so **1,367 lines are reachable only on a device** — the
instrumented tier adds about 60% on top of what the JVM tier alone can see. Where those lines
are:

| Package | JVM | Instrumented | Combined |
| --- | --- | --- | --- |
| `persistance` | 1.3% | **19.2%** | 19.4% |
| `entities` | 8.0% | 7.9% | **13.7%** |
| `utils` | 11.7% | 5.1% | **16.3%** |
| `wireguard` | 60.6% | 28.2% | **78.6%** |
| `crypto/axolotl` | 0.0% | **2.7%** | 2.7% |
| `services` | 0.0% | **0.5%** | 0.5% |

`DatabaseBackend` alone accounts for 468 of the 1,367, then `Message` (130),
`OkHttpClientProvider` (118), `KeystoreCredentialStore` (82), `UnifiedPushDatabase` (79),
`Conversation` (66), `Account` (65), `WireGuardStateMonitor` (60), `NotificationService` (43),
`FingerprintStatus` (42) and `GlacierGuardian` (41). That is the real argument for this tier:
the store, the entity layer and the Keystore/OMEMO paths are things the JVM tier structurally
cannot reach, and they are where a silent break costs user data.

**`ui/` was exactly 0% when the figures above were measured, and is not any more.** Two tiers now
enter an activity: [The UI tier](#the-ui-tier) and [Journey tests](#journey-tests). **The figures
in this section predate both — re-measure rather than quoting them.**

Most of `ui/` is still untouched. It remains the largest package in the module by a wide margin,
and the journey tier reaches the part of it a signed-out device can. Measured on its own
(`api34Atd`, `playstore`, 2026-09-23, running only `ui.journey`):

| Package | Before | Journey package alone |
| --- | --- | --- |
| `ui` | 0% | 1,934 / 19,757 — **9.8%** |
| `ui/adapter` | 0% | 596 / 3,496 — 17.0% |
| `ui/util` | 16 lines | 226 / 1,622 — 13.9% |
| all `ui*` together | ~0% | 2,928 / 29,599 — **9.9%** |
| `services` | 0.5% | 1,411 / 10,326 — 13.7% |
| whole module | 2.2% | 7,372 / 73,476 — **10.0%** |

`services/` moves almost as much as `ui/` does, which is the part worth knowing: entering an
activity binds `XmppConnectionService`, so a journey test exercises the service's startup, its
database restore and its notification paths as a side effect of doing anything at all. That is
reach no amount of further persistence testing was going to buy.

## Journey tests

`ui/journey/` drives the real UI with Espresso. It is the only tier that answers "can a user open a
chat and send a message", and it is what took `ui/` off 0%.

```bash
./gradlew api34AtdPlaystoreDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.glaciersecurity.glaciermessenger.ui.journey
```

It needs **no account, no credentials and no network**, because of one fact about the app:
`ConversationActivity` (the launcher) decides where to go by asking whether the `accounts` table has
any rows, and nothing else. One seeded row reaches the conversation list. `SeedFixtures` seeds it
**disabled**, which is what keeps the tier hermetic rather than merely offline —
`XmppConnectionService` only reconnects `isEnabled()` accounts, and `ExceptionHelper.checkForCrash`
returns false when no enabled account exists, so the crash-report dialog cannot fire either.

### Two dialogs block everything, and they look identical

Both render in their own window, so every Espresso assertion behind one fails with
`NoMatchingViewException` and a hierarchy dump **of the dialog** — which reads as "the conversation
list never rendered" and sends you after the wrong thing entirely. Both are titled *Reauthentication
required*, so a screenshot does not tell you which one you hit. They are not the same, and
suppressing only the second changes nothing:

1. **The Cognito migration notice** is the one that actually fires.
   `XmppActivity.maybeShowMigrationReauthenticationDialog` shows it whenever
   `CognitoClientMigrationManager.isMigrationReauthV1Needed` is true, which is whenever a preference
   has not been written — so on *every freshly installed app*, which is exactly what
   `clearPackageData` hands every test method. It needs no network at all.
2. **The session-expiry prompt**, broadcast by `OkHttpClientProvider` when a JWT-bearing request
   finds a bad session — which a hermetic test always has.

`JourneyEnvironment` suppresses both using the app's **own public** setters
(`markMigrationReauthV1Completed`, `notifyReauthenticationSnoozed`); nothing test-only was added to
`src/main`. It also pre-grants every dangerous permission and writes the battery-optimisation
preference, so no system dialog is ever raised and none has to be driven.

### Rules for adding one

- **`onData` for messages, `onView` for conversations.** `@id/messages_view` is a `ListView` over an
  `ArrayAdapter<Message>`; `@id/list` in the overview is a `RecyclerView`. Never
  `onView(withId(R.id.message_body))` — that id lives in `item_message_content.xml`, included by
  both the sent and received row layouts, so it matches once per visible row and throws
  `AmbiguousViewMatcherException`. Never assert by adapter position: status rows and date separators
  move them.
- **Send with `pressImeActionButton()`.** `ConversationFragment` handles `IME_ACTION_SEND`. The send
  button is *always present and enabled* — the claim in `ui-flow.sh` that `textSendButton` "only
  EXISTS once the input is non-empty" is true of the accessibility tree, not the view hierarchy, and
  does not apply here.
- **Wait with `Wait.until`, not on idleness.** Every `XmppActivity` binds `XmppConnectionService` and
  populates on `onServiceConnected`, and the service restores conversations on a background executor
  — the main looper goes idle several times before anything is on screen. A bounded poll is used
  rather than an `IdlingResource` so that nothing has to be added to `src/main`.
- **Do not drive the launcher with `ActivityScenario`.** `ConversationActivity` calls `finish()`
  inside `onCreate` and never reaches RESUMED; use `Launchers`, which uses an `ActivityMonitor`.
  (`ConversationsActivity` itself *is* drivable by `ActivityScenario` despite being `singleTask` —
  measured, not assumed.)
- **No new dependencies.** `espresso-intents` is unnecessary given `ActivityMonitor`, and
  `espresso-contrib` is avoided by keeping seeded lists short enough not to scroll.
- **Launcher routing belongs to [The UI tier](#the-ui-tier), not here.** `LaunchRoutingTest` owns
  it and covers more than a journey test did: both destinations *and* that the trampoline finishes
  itself, which is what stops a back-stack loop. A journey test that duplicated the two
  destinations was deleted rather than kept beside it. Journey tests start from the screen the
  launcher has already chosen.

## Sharing devices

`connectedAndroidTest` installs on **every attached device**, including emulators and phones
another build may be using. Always export
`ANDROID_SERIAL` first. Managed devices do not have the problem — they are private to the build.

Before trusting a long run on a shared device, record
`adb shell dumpsys package com.glaciersecurity.glaciermessenger | grep lastUpdateTime` at the
start and check it again at the end. If it moved, someone else installed over you mid-run.

## Test data

Same rule as the JVM tier: **no real accounts, numbers, orgs or hostnames.** Fixtures reach CI
logs and anything that scrapes the repo, and a real JID in a fixture invites a test to be pointed
at the real backend. Build fixtures through `testutil/DeviceTestFixtures`, which uses
`example.com` (RFC 2606) throughout. The full table of reserved ranges is in
[`src/test/README.md`](../test/README.md).

## Adding an activity-level test

An activity declared in `src/androidTest` belongs to the *test* APK and the instrumentation's
target process cannot launch it. Add it to the **debug** source set instead, next to
`WindowFlagsProbeActivity` — `src/debug/java/.../ui/util/` plus a declaration in
`src/debug/AndroidManifest.xml`. It stays out of release builds.

Note that `SettingsUtils.applyScreenshotSetting` detects instrumentation and leaves `FLAG_SECURE`
off. Without that, a failing UI test would be undebuggable: `screencap` returns black and
`uiautomator dump` returns nothing, so there is no failure screenshot and no view hierarchy.
`ScreenshotSettingTest` is what keeps that arrangement in place.
