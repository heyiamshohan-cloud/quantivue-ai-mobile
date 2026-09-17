# Privacy and security

* Screen capture is user-approved by Android and visible through a foreground notification.
* Frames remain in memory for analysis and are released after processing; there is no network permission or upload path.
* No credentials, passwords, tokens, account identifiers, accessibility events, or private Quotex APIs are collected.
* Local SQLite stores prediction/audit metadata only. The app does not store raw frames by default.
* No signing keys, passwords, or tokens are in the repository. Release signing uses the installer's own keystore.
* The overlay is optional and requires the system's `Display over other apps` permission.

Users can stop capture from the app or system notification and can revoke projection permission. This app is analysis/decision support, not an order-execution client.
