# Changelog

Guardian by Glacier for Android. Only user-visible changes are listed. Guardian is a
fork of [Conversations](https://github.com/iNPUTmice/Conversations); for upstream
history, see that project's changelog.

## 7.3.3 (2026-10-15)

- Guardian no longer asks for photo access at launch.
- Attachment lists no longer stop early on a malformed message.

## 7.3.2 (2026-10-14)

- Bluetooth headsets: calls ring on the headset and can be answered with its button,
  and the in-call audio picker lists every connected headset.
- Optional Telecom integration for Guardian-to-Guardian calls on Android 15 and later.
- Leaving a group archives it on all of your devices.
- Password-protected PDFs prompt for their password.
- Core VPN status shows correctly when another VPN takes over.

## 7.3.1 (2026-09-30)

- The local database is opened through SQLCipher, and account credentials are kept
  in the Android Keystore.
- Expired messages disappear in place instead of reloading the conversation.
- Fixes for sending OpenPGP-encrypted messages and for camera attachments.

## 7.3 (2026-09-14)

- Restoring a backup is more reliable, and failures say what went wrong.
- Call screen and call notification fixes.

## 7.2.2 (2026-09-03)

- Smaller APK: native debug symbols are stripped and only phone ABIs ship.
- Chat and calls recover after switching between Wi-Fi and mobile data.
- Fewer repeated sign-in prompts.
- A warning when the phone has stopped receiving calls.
- Layout fixes for landscape, small screens and the navigation bar.

## 7.2.1 (2026-08-29)

- Conversation media is recovered after the move to app-owned storage.
- Group chats rejoin after a reconnect.
- Call routing fixes when Android's Telecom service refuses a call.
- Battery optimization prompts no longer dead-end.

## 7.2 (2026-08-07)

- Baseline for this changelog.
