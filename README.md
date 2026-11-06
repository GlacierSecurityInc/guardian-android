# Glacier Security

Glacier Security is a security-focused Android application that helps teams protect sensitive communications, manage access, and maintain operational visibility in one place. This repository contains the mobile client and supporting tooling for building, testing, and shipping the app.

## Highlights

- Secure messaging and collaboration with privacy-first defaults.
- Device and session controls for safe access on managed or unmanaged devices.
- Audit-ready activity tracking to support operational compliance.
- Modern Android UX with a focus on performance and accessibility.

> **This is a modified fork of [Conversations](https://github.com/iNPUTmice/Conversations)
> by Daniel Gultsch, distributed under the GPLv3.** Substantial portions of the
> XMPP, OMEMO, and Jingle implementations originate there and remain under their
> original copyright. See [Credits and attribution](#credits-and-attribution).

## Getting started

### Prerequisites

- Android Studio (latest stable)
- Android SDK + build tools
- JDK 17

### Build configuration

**The repository does not build as-is.** Several configuration files carry
deployment-specific credentials and are deliberately excluded from version
control. You must supply your own before building:

| Path | Purpose |
| --- | --- |
| `src/main/res/raw/awsconfiguration.json` | AWS mobile client configuration |
| `src/main/res/raw/amplifyconfiguration.json` | Amplify (Cognito auth) configuration |
| `src/main/java/com/glaciersecurity/glaciermessenger/EndPointConfig.java` | Service endpoint constants |
| `src/main/res/values/glacier_security.xml` | Deployment-specific string resources |
| `src/main/assets/config.properties` | Runtime configuration properties |
| `google-services.json` | Firebase project configuration |

Two Gradle properties are also required, normally set in `~/.gradle/gradle.properties`:

- `MAPBOX_DOWNLOADS_TOKEN` — read access to the Mapbox Maven repository
- A `keystore.properties` file in the project root (`storeFile`, `storePassword`,
  `keyAlias`, `keyPassword`) if you intend to produce signed release builds

## Repository structure

- `src/` — Android app source code
- `docs/` — Developer documentation
- `libs/` — Local annotation processor modules

## Security

Use GitHub's private vulnerability reporting under the **Security** tab, or email
**support@glacier.chat** with `SECURITY` in the subject.

See [SECURITY.md](SECURITY.md) for supported versions, scope, and what to include
in a report.

## License

This project is licensed under the GNU General Public License v3.0. See
[LICENSE](LICENSE) for the full text.

---

## Credits and attribution

Guardian by Glacier is a GPLv3 fork of
**[Conversations](https://github.com/iNPUTmice/Conversations)** by
**Daniel Gultsch**, and would not exist without that work. Our sincere thanks to
Daniel and to everyone who has contributed to Conversations over the years.

The dependency is not incidental. As of this release the fork still carries:

- **250 files** under `src/main/java/im/conversations/` — the XMPP core, including
  stanza models, OMEMO/axolotl, Jingle, and the connection layer
- **7 files** under `src/main/java/de/gultsch/` — the bundled minidns resolver
- **64 files** carrying a Daniel Gultsch copyright header

Those headers are authoritative and must stay intact in any further
redistribution. Conversations is itself GPLv3, which is why this repository is
GPLv3 and why the full source is published here.

Also included under their own copyright and notices:

- **Belledonne Communications SARL** (Linphone) — 3 files
- **Georg Lukas** — MemorizingTrustManager
- **The WebRTC project** (Google) — audio and peer-connection management
- **Régis Montoya** (CSipSimple) — logging utilities

### About this fork

This repository publishes Glacier Security's `7.3` line
(`com.glaciersecurity.glaciermessenger`, version 7.3.3). It diverges
substantially from upstream Conversations, adding device security posture
checks, Core Connection (WireGuard) integration, Cognito-backed accounts,
and telephony and SMS.

Because of that divergence we do not track upstream releases directly, and
changes here are not submitted back to Conversations. **Please report issues
with this fork here rather than to the Conversations project** — they are not
responsible for our modifications, and this repository is not affiliated with or
endorsed by them.
