# Security Policy

Glacier Security builds tooling for teams handling sensitive communications, and
we take reports about this client seriously. Thank you for taking the time to
report an issue responsibly.

## Reporting a vulnerability

**Please do not open a public GitHub issue for a security problem.**

Report it one of two ways:

1. **GitHub private vulnerability reporting** — use the **Report a vulnerability**
   button under this repository's **Security** tab. This keeps the report private
   until a fix ships and gives us a place to discuss it with you.
2. **Email** — [support@glacier.chat](mailto:support@glacier.chat) with `SECURITY`
   in the subject line.

If you do not get an acknowledgement within a few business days, please follow up
by the other channel rather than assuming the report was received — mail does get
filtered.

### What to include

The more of this you can provide, the faster we can confirm and fix:

- The affected version (**Settings → About**, or the `versionName` in a build)
- Device model and Android version
- What an attacker can achieve, and what access they need to start
- Reproduction steps, or a proof of concept
- Relevant logs or stack traces, with your own identifiers and message content
  removed

### What to expect

We aim to acknowledge reports promptly, confirm or dispute the finding after
triage, and keep you updated while we work on a fix. We will tell you when a fix
ships and, unless you would rather stay anonymous, credit you in the release
notes. If we decide not to act on a report, we will explain why.

Please give us a reasonable opportunity to ship a fix before disclosing publicly.

## Supported versions

Fixes land on the current release line. Older lines are not backported.

| Version | Supported |
| --- | --- |
| 7.3.x | Yes |
| 7.2.x and earlier | No |

The app targets Android 11 (API 30) and above.

## Scope

**In scope** — anything in this repository that affects the confidentiality,
integrity, or availability of user communications or credentials. For example:

- Flaws in message encryption, key handling, or trust decisions (OMEMO/axolotl)
- Authentication, session, or token handling problems
- Leakage of message content, credentials, or keys through logs, backups,
  exported files, or IPC
- Exported components, deep links, or intents that can be abused by another app
  on the device
- Certificate validation or transport security weaknesses

**Out of scope** — reports we are unlikely to act on:

- Findings that require a rooted or already-compromised device, or physical
  access to an unlocked device
- Automated scanner output with no demonstrated impact
- Vulnerabilities in third-party dependencies with no exploitable path in this
  app — please report those upstream
- Missing hardening that has no concrete attack behind it
- Social engineering, and anything targeting Glacier Security staff or
  infrastructure rather than this client

Server-side and account infrastructure is not in this repository. Send those
reports to [support@glacier.chat](mailto:support@glacier.chat) as well, and note
that they concern infrastructure rather than the app.

## A note on upstream code

This application is a fork of
[Conversations](https://github.com/iNPUTmice/Conversations) by Daniel Gultsch,
and a large amount of the XMPP, OMEMO, and Jingle code originates there. See
[Credits and attribution](README.md#credits-and-attribution).

If you find a vulnerability that also exists in unmodified upstream Conversations,
please report it to that project as well — their users are affected too, and they
are not covered by anything we do here. If you are unsure whether a finding is
ours or upstream's, report it to us and we will work that out and coordinate.

## Please do not

While researching, avoid anything that harms real users:

- Accessing, modifying, or exfiltrating accounts or data that are not yours
- Degrading service for other users
- Testing against Glacier Security production infrastructure without written
  permission — test against your own build and your own accounts

Research conducted in good faith under this policy is welcome, and we will not
pursue action over it.
