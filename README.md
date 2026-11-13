# Keyra

[English](README.md) | [Русский](README.ru.md)

A lightweight and fast password manager for Android that works with KeePass KDBX databases. Store passwords on your device, use them in apps and browsers, and sync your database with other devices through the storage of your choice.

Keyra is an open-source project: anyone can inspect its source code to review how the app works and protects their data.

Keyra features:

- Database protection with a master password, a key file, or a combination of both.
- Quick unlock using biometrics or your device's screen lock.
- Autofill for usernames and passwords in Android apps and supported browsers.
- Synchronization through WebDAV and files available in Android's system file picker.
- Offline access to your local database.
- Password generator, search, groups, tags, and entry change history.
- Compatibility with desktop password managers that support the KDBX format, such as KeePass and KeePassXC.

## Security

### KDBX database protection

Passwords and other data are stored in an encrypted KDBX file. The format supports encryption and master key derivation that makes password guessing harder. The strength of protection depends in part on your chosen password and database settings.

To open a database, you can use:

- **A master password** — a secret you memorize and enter when unlocking.
- **A key file** — a separate file containing an additional secret.
- **A master password and a key file together** — in this case, both components are required to open the database.

If an attacker obtains only the database file, they will still need its master key. When both forms of protection are used, knowing the password without the key file or having the key file without the password is insufficient for normal decryption. Use a long, unique master password and keep a backup of your key file separate from the database: losing a required component means losing access to your data.

For more information about the composite master key, see the [KeePass documentation](https://keepass.info/help/base/keys.html).

### Protection on your device

Keyra stores an encrypted local copy of the key file in the app's private data. Encryption uses AES-GCM, with the key stored in Android Keystore.

If you enable quick unlock, the data needed to open the database, including the master password, is also stored in encrypted form. Using the decryption key requires authentication through Android's system prompt. You can use a fingerprint, another form of Strong-class biometrics supported by your device, or your screen lock PIN, password, or pattern. You do not need to enter the database's master password every time you open it.

The app also provides automatic database locking and clears copied secrets from the clipboard after a specified time.

## Integration

You can optionally set Keyra as your Android autofill service. Compatible apps and browsers will then offer to fill usernames and passwords using entries from your database. For websites, entries are matched by the address saved in the entry; for apps, you can configure the corresponding associations.

If the database is locked, you will need to unlock it before filling. Autofill support depends on the particular app, browser, and login form. In Chrome, you may also need to allow a third-party autofill service in the browser settings.

## Synchronization

You can use the same KDBX database on multiple devices, for example in Keyra on Android and KeePassXC on your computer. Configure both devices to access the same file in shared storage. After synchronization, changes made on one device will be available on the other.

Keyra keeps an encrypted local copy of the database so you can work offline. Changes are uploaded when the chosen storage is available and the database is unlocked. The app checks for updates when opened, after editing, and while its process is running. Synchronization is not necessarily instant: it is affected by Android restrictions, the network, and the cloud client's behavior. On your computer, synchronization must also be handled by your chosen password manager or file client.

When changes occur on both sides, Keyra attempts to merge the data; if there is a conflict or a write requires confirmation, the app notifies you.

### Through the system file picker

If your database is stored in Google Drive, Dropbox, or another cloud service, install the service's client or a file provider that supports reading and writing files through Android's system file picker. Then select the database file in Keyra's synchronization settings.

Simply installing a cloud client is not enough: it must support this type of file access. Storage availability and update speed depend on the chosen provider. You can read about the system mechanism for accessing local and cloud files in the [Android documentation](https://developer.android.com/guide/topics/providers/document-provider).

### Through WebDAV

Keyra supports a direct connection to a WebDAV server: enter the database file's address and your credentials. No separate cloud client is required. Use HTTPS to protect the connection.

WebDAV is a [standard HTTP extension for working with remote files](https://www.rfc-editor.org/info/rfc4918/). It is supported by a number of cloud services, NAS devices, and self-hosted servers, but it is not a universal protocol for all cloud services. Before configuring it, make sure your service provides WebDAV access.

Synchronization is not a substitute for backups: keep separate copies of your database and key file.

## Requirements

- **Minimum:** Android 11 (API 30).
- **Recommended:** Android 15 (API 35) or later with up-to-date security patches.
- The current build targets Android 16 (target API 36); this does not raise the minimum Android version.
- Quick unlock requires a screen lock to be configured on the device; biometrics require a compatible sensor.
- Network synchronization requires access to the chosen storage. The local database can be used without an internet connection.

The mapping between Android versions and API levels is available in the [Android documentation](https://developer.android.com/guide/topics/manifest/uses-sdk-element).

## License

Keyra's own code is licensed under **GNU GPL version 3 or later** (`GPL-3.0-or-later`). See [LICENSE](LICENSE) for the full text and [LICENSING.md](LICENSING.md) for scope and third-party exceptions.

Third-party components retain their own licenses and notices. See [THIRD_PARTY.md](THIRD_PARTY.md).
