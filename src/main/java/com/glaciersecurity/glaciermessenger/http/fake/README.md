# The fake Glacier backend

A debug-only stand-in for the Glacier console. With it on, every call to the mobile API is
answered from memory: no console, no Cognito session, no Twilio, and no way for a test to buy a
real phone number or release somebody's.

```bash
./gradlew installPlaystoreDebug -PglacierFakeBackend=true
```

That is the whole setup. There is no server to start and no base URL to change — which matters,
because the base URLs live in the gitignored `EndPointConfig.java` and a local URL could not be
added there without editing a secret that never reaches code review.

## How it works

`FakeGlacierBackend` is an OkHttp `Interceptor`, installed **first** in
`OkHttpClientProvider.createClient()`. Everything in the app reaches the backend through that one
client — raw OkHttp calls, the Retrofit `MobileApiService`, and the multipart uploads — so one
interceptor covers the whole surface.

Being first is what makes the rest work:

- **No login needed** -- but this takes two things, not one. Being first in the chain means the
  JWT *interceptor* below never runs. That is not sufficient on its own: most of the phone
  module builds its requests through `buildJwtFormRequestBuilder`, which calls `addJwtHeaders`
  -> `getCognitoTokens()` while *building* the request, before `newCall()` reaches any
  interceptor. On a signed-out device that can attempt a silent sign-in or block on the
  reauthentication latch, so the request would never arrive here at all. `addJwtHeaders`
  therefore returns early when the fake is enabled -- the header is meaningless when nothing
  leaves the process. Both halves are needed for a populated phone module while signed out.
- **Pinning is irrelevant.** No socket is opened, so `GlacierCertificatePinner` is never
  consulted. This is *not* the `-PglacierInsecureTestBackend=true` path and does not need it;
  that flag is for pointing the app at a real local MockWebServer over TLS.
- **Only Glacier traffic is faked.** Requests whose path is not under `/api/mobile/` or
  `/mobile/v1/input` are passed through untouched, so XMPP HTTP-upload slots, avatars and media
  downloads still go to the network. The fake stands in for the console, not for the internet.

## It has state, not canned files

`FakeTenant` holds what a real tenant would: owned numbers, a pool of purchasable ones,
voicemails and call history per number, and the org-level switches. Purchasing a number moves it
out of the pool and into the selected list; releasing it moves it back; renaming sticks. So the
add/remove flows can be walked end to end and actually change something, instead of returning the
same fixture no matter what was asked.

Nothing is persisted. State is rebuilt at process start, which is also what makes this usable
under the instrumented tier, where the orchestrator gives every test method a fresh process.

## Forcing failures

The interesting paths in this app are the unhappy ones, and several were unreachable before:
`handleWebhookJsonSyntaxException` and the `consoleError` banner it raises fire only on a 200
carrying something that is not JSON, which is what a gateway returns when it answers instead of
the API.

Scenarios are set per endpoint, keyed by the **last path segment**, without rebuilding:

```bash
PKG=com.glaciersecurity.glaciermessenger
A=$PKG.FAKE_BACKEND

adb shell am broadcast -p $PKG -a $A -e endpoint purchaseTwilioNumber -e scenario failure
adb shell am broadcast -p $PKG -a $A -e endpoint voicemail_list       -e scenario malformed
adb shell am broadcast -p $PKG -a $A -e endpoint call-history         -e scenario network_error
adb shell am broadcast -p $PKG -a $A -e endpoint all                  -e scenario http_500

adb shell am broadcast -p $PKG -a $A -e command reset     # all endpoints back to OK
adb shell am broadcast -p $PKG -a $A -e command reseed    # and the tenant to its starting state
adb shell am broadcast -p $PKG -a $A -e command sms      --ez value false
adb shell am broadcast -p $PKG -a $A -e command purchase --ez value false
adb shell am broadcast -p $PKG -a $A -e command status
```

**`-p $PKG` is not optional.** Since Android 8 a manifest-declared receiver cannot be woken by
an implicit broadcast, so the bare `am broadcast -a $A` form reports `Broadcast completed:
result=0` and does absolutely nothing -- there is no error to notice. `-p` narrows the intent to
this package, which is enough to make it explicit. `-n $PKG/$PKG.http.fake.FakeBackendReceiver`
works too and is what to reach for if a future Android tightens this further.

Read the result back with `adb logcat -d | grep FakeBackendReceiver`; every command logs the
resulting state. Note the app logs nothing unless the build has `Config.logLevel` above 0.

| Scenario | What the app sees | Reaches |
| --- | --- | --- |
| `ok` | the endpoint's normal answer | the happy path |
| `failure` | 200 `{"message":"failure"}` | every `message.equals("success")` else-branch |
| `http_500` | 500 with an HTML body | the `response.code()` checks |
| `empty` | 200, zero-length body | `ConversationsManager`'s empty-body guard |
| `malformed` | 200, HTML, `Content-Type: application/json` | `handleWebhookJsonSyntaxException`, `consoleError` |
| `network_error` | throws `IOException` | every `catch (Exception)` and `onFailure` |

An unrecognised scenario name resolves to `ok`, so a typo clears the override rather than leaving
a stale one in place.

## Adding an endpoint

Add a `case` to `FakeGlacierBackend.ok()`, keyed by the last path segment. An unrecognised Glacier
path answers **501**, deliberately — a default of `{"message":"success"}` would make an endpoint
nobody implemented look like it worked.

Derive the response shape from the app's own parser, not from a captured payload. The shapes here
came from `SMSdbInfo`, `MobileApiResponses`, `HistoryItem`, `VoicemailRecord` and
`TwilioAccessTokenParser`, which is why the tests assert by deserialising into those same DTOs.
A fixture that only matches itself is worth nothing: the failure this guards against is the fake
and the parsers drifting apart, at which point a green pass against the fake says nothing about
the real console.

Watch the key spellings. Several are easy to get wrong and silently produce an empty list:

| Endpoint | Key | Note |
| --- | --- | --- |
| `getUserSelectedTwilioNumbers` | `text` / `id` | `text` is the **number**, `id` the SID |
| `voicemail_list` | `recordingsid`, `callsid` | lower case, not camelCase |
| `is-live` | `is-live` | hyphenated |

## What it does to an account already signed in on the device

The fake answers `glacier-users`, and the app treats that record as authoritative: on every
startup and periodic refresh, `XmppConnectionService.queryGlacierUsers` writes the returned
`xmpp_token` onto the account with `setToken` and calls `databaseBackend.updateAccount`. With no
token it leaves the stored credentials alone; the backend is JWT-only and the client never takes
a password from this record.

So a fake that filled in `xmpp_token` would replace the real credential of whatever account is
signed in, **persistently**: the value lands in the account row, survives reinstalling a normal
build, and the next real build fails SASL with *"account has an XMPP token but server does not
offer PLAIN"*. That is why `glacierUser()` omits it and `FakeGlacierBackendTest` has a regression
guard for it.

With both absent, neither branch runs and the stored credentials are untouched. If you hit this
anyway on an older build of the fake: it self-heals. Install a normal build, let it sync against
the real backend once, and `setToken` overwrites the placeholder with the real token.

The rest of the record is still fake, so while this build is installed the app's idea of the org
and its flags comes from `FakeTenant`, not from the console.

## No real data, ever

Every value is from a range reserved for documentation: `202-555-01xx` phone numbers (the block
reserved for fiction), `example.com` hosts, `192.0.2.0/24` addresses, `acme` as the org. The JWT
is constructed — it decodes to `{"sub":"guardian"}` with the literal signature `signature`, the
same fixture `TwilioAccessTokenParserTest` uses.

This is not decoration. These classes compile into debug APKs that get passed around, and the
temptation when reproducing a customer bug is to paste in the real payload. Don't: put the real
shape in, with reserved values. The rule and the full table of ranges are in
[`src/test/README.md`](../../../../../../../test/README.md).

## Why it cannot ship

Two independent conditions gate it, the same arrangement `GlacierCertificatePinner` uses:
`BuildConfig.DEBUG` cannot be true in a store build, and `FAKE_BACKEND` is hardcoded `false` for
the release build type in `build.gradle`. Neither a mistyped Gradle property nor an edit to this
file alone can ship an app that serves itself fake data.

Both are compile-time constants, so R8 folds the branch and removes the code. That needed one
change to make true: `proguard-rules.pro` keeps
`com.glaciersecurity.glaciermessenger.**` wholesale, which would have left these class shells and
their fixture strings in a store APK. The rule now carries a negated filter excluding
`http.fake`. Verified with:

```bash
./gradlew minifyPlaystoreReleaseWithR8 --offline
strings build/intermediates/dex/playstoreRelease/minifyPlaystoreReleaseWithR8/*.dex \
  | grep -E "FakeGlacierBackend|12025550142"     # no output
```

If you ever widen that keep rule again, re-run this. Everything else about the gate is invisible
until somebody disassembles a release build.

## What it does not do

- **It is not a backend contract test.** It answers what the app's parsers expect, which is not
  necessarily what the console sends. A change verified only against the fake has not been
  verified. It shortens the loop for UI and flow work; it does not replace testing against a real backend.
- **No Twilio SDK.** This is the one that surprises people. The fake answers
  `getAccessToken` / `getAccessTokenForDial` happily, but the Twilio Voice and Conversations
  **SDKs** then take that token to Twilio's own servers over their own transport -- not through
  OkHttp -- and Twilio rejects it: `Registration Error: 20101, Capability tokens are not
  supported` and `Error creating Twilio Conversations Client: UNAUTHORIZED`, surfacing in the UI
  as *"Unable to refresh Phone data. Tap to retry."* No interceptor can fix that.

  So the phone module's **number management** works fully against the fake -- the selected list,
  Add Number, Release Number, Nickname, call history, the voicemail list and its unread badge --
  and **sending or receiving an actual SMS or call does not**. Expect the red banner; it is not a
  sign the fake is broken.
- **No XMPP.** Chat, presence, OMEMO and calls go over the XMPP connection, not this API. The
  fake serves the `glacier-users` record that login reads, but it does not stand up a server for
  the account in it to connect to.
- **No Cognito.** Sign-in itself is Amplify, not OkHttp, so the login screen is unaffected. The
  fake is reached by everything *after* login — and, because it needs no token, also by
  everything before it.
