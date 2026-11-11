# Privacy Policy — RegexPhone

**Effective date:** 11 November 2026

RegexPhone is an Android app that screens incoming phone calls against rules
you define. This policy explains what the app does and does not do with your
information.

## Summary

RegexPhone collects nothing, sends nothing, and contains no analytics,
advertising, or third-party SDKs. Everything happens on your device.

## Data the app processes

- **Your rules.** The regular expressions and actions you create are stored
  locally on your device in the app's private storage (device-protected
  `SharedPreferences`). They are never transmitted anywhere.
- **Incoming phone numbers.** When a call arrives, Android passes the caller's
  number to the app's call-screening service. RegexPhone matches that number
  against your rules in memory to decide whether to allow, reject, or silence
  the call, or to warn you about it. The number is not stored, logged, or
  transmitted by the app.
- **Warning notifications.** If you create a rule with the optional `NOTIFY`
  action, a matching call posts a local notification showing the rule name and
  the caller's number. It is handled by Android's notification system on your
  device like any other notification and is never sent anywhere.

## Data the app does NOT collect or access

- No internet or network access — the app declares no `INTERNET` permission.
- No contacts access.
- No call log access (`READ_CALL_LOG` is not requested).
- No location, advertising identifier, or device identifiers.
- No analytics, crash reporting, or tracking of any kind.

## Permissions

RegexPhone works by becoming your device's call-screening app (the
call-screening role), which you grant explicitly in the system dialog and can
revoke at any time in Android settings.

The only runtime permission it holds is the notification permission
(`POST_NOTIFICATIONS`). On Android 13 and later Android grants it together
with the call-screening role, so the app never asks for it. It is used only to
post the warning of a `NOTIFY` rule: if you never use `NOTIFY`, no notification
is ever posted. You can turn notifications off for the app in Android settings;
that only stops the warnings, and every other action keeps working.

## Storage and backup

Your rules are deliberately excluded from Google cloud backup and
device-to-device transfer, because the rule set can reveal whom you block. To
move rules between devices, use the in-app Export/Import feature, which writes a
JSON file you control.

## Children

RegexPhone is not directed at children and collects no data from anyone.

## Changes

If this policy changes, the updated version will be published at this URL with a
new effective date.

## Contact

Renaud Allard — renaud@allard.it
Source code: https://github.com/renaudallard/regexphone
