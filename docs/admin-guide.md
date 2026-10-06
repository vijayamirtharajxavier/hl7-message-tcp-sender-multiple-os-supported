# Administrator guide

For people who deploy HL7 Sender on several machines, run it unattended, or are responsible for the data it
holds. See the [user guide](user-guide.md) for day-to-day use.

## Silent installation and removal

| System | Install | Remove |
|---|---|---|
| Windows | `msiexec /i "HL7 Sender-X.Y.Z.msi" /qn` (per-user; run as the user who will use it) | `msiexec /x "HL7 Sender-X.Y.Z.msi" /qn` |
| macOS | `sudo installer -pkg "HL7 Sender-X.Y.Z.pkg" -target /` | Delete `/Applications/HL7 Sender.app` |
| Debian/Ubuntu | `sudo apt install ./hl7-sender_X.Y.Z_amd64.deb` | `sudo apt remove hl7-sender` |
| Fedora/RHEL | `sudo dnf install ./hl7-sender-X.Y.Z-1.x86_64.rpm` | `sudo dnf remove hl7-sender` |

Linux packages install to `/opt/hl7-sender` and link `/usr/bin/hl7send`. They install on servers without a
desktop; the menu entry is skipped there. Upgrading installs over the previous version (the Windows installer
has a fixed upgrade code). **Removing the application never removes settings, the queue or logs**; delete the
folders below to remove them.

Verify downloads against `SHA256SUMS-<OS>.txt` from the release:

```bash
sha256sum -c SHA256SUMS-Linux.txt --ignore-missing
```

## Where data lives

| | Windows | macOS | Linux |
|---|---|---|---|
| Settings | `%APPDATA%\HL7Sender` | `~/Library/Application Support/HL7Sender` | `$XDG_CONFIG_HOME/hl7sender` (`~/.config/hl7sender`) |
| Queue database | `%LOCALAPPDATA%\HL7Sender\data\queue.db` | `~/Library/Application Support/HL7Sender/data/queue.db` | `$XDG_DATA_HOME/hl7sender/queue.db` |
| Logs | `%LOCALAPPDATA%\HL7Sender\logs` | `~/Library/Logs/HL7Sender` | `$XDG_STATE_HOME/hl7sender/logs` |

`HL7SENDER_HOME=DIR` (or `-Dhl7sender.home=DIR`, or `hl7send --home DIR`) keeps everything in one folder, for
example on a portable drive or a dedicated service directory.

The settings folder holds `settings.json` (app, alert and API settings), the local API token (`api-token`) and
endpoint (`api.json`), and, when no OS keychain is available, `secrets.json` with its key `secrets.key`. The
files holding secrets are readable only by their owner. Destinations are stored in the queue database.

## Protecting PHI

- The **queue database contains message content**. Treat it like any clinical data store: restrict access to the
  user's profile, include it in your data-retention policy, and turn on **Help > Security > Encrypt the queue
  database** (AES-256). Keep the recovery key (**Show** in the same dialog) in a safe place; without the key the
  queue cannot be opened.
- **Logs never contain message content**, only type, control ID, outcome and timings. Alerts carry names and
  counts only. Diagnostics bundles leave out content and secrets and mask credential-like text.
- **Passwords** (TLS stores, SMTP, the database key) live in the OS keychain: Windows Credential Manager, macOS
  Keychain, or the Secret Service (GNOME Keyring, KWallet) on Linux. Exported destination profiles never contain
  them.
- Delete messages you no longer need with `hl7send queue delete` or `hl7send dlq delete`, or the local API.

## Network requirements

- **Outbound** TCP to each receiver's host and port (MLLP, optionally TLS), and HTTPS to HTTP and FHIR
  destinations. With a shared database, TCP to the PostgreSQL server (5432 by default).
- **Inbound**: none by default. The test listener opens the port you choose; the local API listens on
  `127.0.0.1:8742` only when turned on.
- **Internet**: none required. The optional update check calls `https://api.github.com`; alerts may call your
  webhook and SMTP server.

## Unattended delivery

Run `hl7send serve` (usually installed with `hl7send service install`) on a machine that should deliver without
anyone logged in to the app. Only one process delivers from a queue at a time: the app or the service. See
[Running as a service](../README.md#running-as-a-service) for systemd, launchd and Windows (WinSW).

Recommended setup for a server:

1. Create destinations in the app on a workstation, export them (**Queue > More > Export destinations...**),
   and load them on the server with `hl7send destination import profiles.json`. Re-enter TLS passwords on the server.
2. Set folder watches for the directories your upstream system writes to.
3. `hl7send service install`, and on Linux `loginctl enable-linger USER` so it runs without a login session.
4. Monitor with alerts (webhook or e-mail), `hl7send queue status --fail-if-dead` from your monitoring system
   (exit code 9 when messages are dead-lettered), or the local API's `GET /destinations`.

## Shared PostgreSQL database

By default each computer has its own queue in a SQLite file. To let several people and services work on one
queue (the same destinations, messages and history), put it on a PostgreSQL server (tested with PostgreSQL 16):

1. Create a database and a user for it, for example `CREATE DATABASE hl7sender; CREATE USER hl7sender PASSWORD
   '...'; ALTER DATABASE hl7sender OWNER TO hl7sender;`. The tables are created on first use.
2. On each computer, choose **Tools > Queue database...** in the app, or run
   `hl7send database use-postgres jdbc:postgresql://db.example.org:5432/hl7sender?sslmode=verify-full
   --db-user hl7sender`. The connection is tested before it is saved. The password goes to the OS keychain.
3. Restart the app and any `hl7send serve` service.

One process delivers at a time: the first app window or service to start takes a lock on the server, and the
others work with the queue without delivering. If the delivering process stops or loses its connection, the next
one to start takes over. The delivering process re-reads the queue every 5 seconds, so work added on another
computer is sent within seconds. For unattended delivery, run `hl7send serve` on a server so delivery does not
depend on someone's app window.

Notes:

- Use `sslmode=verify-full` (with the server's CA in the Java trust store or `sslrootcert=...`) so the connection
  is encrypted and the server is verified. Other PostgreSQL JDBC URL parameters work too.
- Several separate queues can share one database, each in its own schema: add `&currentSchema=team_a` to the URL.
- The app's **Encrypt the queue database** option applies to SQLite only. Protect a PostgreSQL queue with the
  server's own encryption at rest and access controls.
- The local queue is not copied. Export destinations first (**Queue > More > Export destinations...**) and
  import them after switching. `hl7send database use-sqlite` switches back.
- If the database cannot be reached at start, the queue is unavailable until it can (the app still sends directly).
  A delivering process that loses its connection must be restarted.

## Users and roles

Until the first user is added, anyone who can start the app or `hl7send` can do everything. Add users in
**Tools > Users and roles...** or with `hl7send user add NAME --role admin` (the first user must be an
administrator). From then on, the app asks for a user name and password at start, and the queue commands need
`--user NAME` (or `HL7SENDER_USER`) with the password in `HL7SENDER_PASSWORD`. They exit with code 11 if sign-in
fails or the role does not allow the command.

| Role | Permissions |
|---|---|
| `viewer` | See destinations, queues, history, the dashboard and logs. |
| `operator` | Also send and queue messages, replay, run schedules and load tests, retry, requeue, edit, delete, pause and resume. |
| `admin` | Also destinations, schedules, alerts, the local API, the queue database, security settings and users. |

- `hl7send user list | add | set-role | password | enable | disable | remove` manage users; new passwords come from
  `--password-stdin`, `HL7SENDER_NEW_PASSWORD` or the console. Passwords need at least 8 characters and are stored
  as PBKDF2-SHA256 hashes.
- The last enabled administrator cannot be demoted, disabled or removed. Removing the only user turns sign-in off.
- Sign-ins, failed sign-ins and user changes are in the audit trail, and queue changes are recorded under the
  user's name.
- Users live in the queue database, so a shared PostgreSQL database gives every computer the same users.

What this protects, and what it does not: roles control what people can do through the app and `hl7send`. They
are not a barrier against someone who can read the database directly: the SQLite file (anyone with access to the
OS account) or the PostgreSQL credentials. Keep the database credentials to the service account and trusted
machines. `hl7send serve`, the service it installs, and the local REST API (anyone holding the token) do not sign
in, and act with full access; keep the API token file protected (it is readable only by its owner).

## Transport plugins

Destinations can send over MLLP, HTTP(S), to a folder, or to a FHIR server out of the box. Other transports
(SFTP, message brokers, cloud queues) are added as plugins: a jar implementing
`io.hl7sender.core.transport.TransportFactory`, copied into the `plugins` folder inside the settings folder. The
app and `hl7send serve` load plugins at start; `hl7send destination transports` lists them and any that failed to
load. See `examples/transport-plugin` for a minimal plugin. Plugins run with the app's permissions and can read the
messages they send: install only plugins you trust.

## Backup and moving to another machine

For a shared PostgreSQL queue, back up the database with the usual tools (`pg_dump`). For a local queue, stop
the app or service, then copy the settings and queue folders. The database is SQLite in WAL mode: copy
`queue.db` together with any `queue.db-wal` and `queue.db-shm`, or run `sqlite3 queue.db ".backup copy.db"`.
Passwords stay in the old machine's keychain; re-enter them after moving. An encrypted queue needs its key: copy the
recovery key from **Help > Security** on the old machine and use **Restore key** on the new one.

## Updates

The app checks GitHub Releases only if the user turns on **Check automatically** (off by default). To keep it
off, simply leave it off; to point it at an internal mirror of the GitHub releases API, start the app with
`-Dhl7sender.updateUrl=https://mirror.example/releases/latest`. Nothing is ever downloaded automatically.
Updates are installed by running the newer installer; data folders are kept and the queue database is migrated
on first start.

## Logs

`hl7-sender.log` (all levels) and `hl7-sender-errors.log` (warnings and errors) roll daily and by size. The CLI
writes its own log in the same folder and prints only errors to the console. Services on Linux also log to the
journal (`journalctl --user -u hl7send`).
