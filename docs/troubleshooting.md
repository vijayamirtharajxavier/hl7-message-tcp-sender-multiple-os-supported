# Troubleshooting

Start with the message's details: **History** (or `hl7send queue show ID`) lists every attempt with its outcome,
ACK code, ERR segments and error text. The warnings/errors log in the log folder (**Help > Open log folder**)
has the same information with timestamps. Message content is never written to the logs.

When asking for help, attach a diagnostics bundle (**Tools > Export diagnostics bundle...**). It contains the
system information, settings, destinations, queue counts, recent alerts and logs. Passwords, keys, webhook tokens
and message content are never included, and anything in the logs that looks like a credential is masked.

## Outcomes and what they mean

| Outcome | Shown as | Default action | Usually means |
|---|---|---|---|
| Accepted (AA/CA) | green | complete | Delivered. |
| Sent, no ACK expected | green | complete | **Wait for ACK** is off. Delivery is not confirmed. |
| Application error (AE/CE) | red | dead-letter | The receiver read the message but its content is wrong. Resending the same message will fail again. |
| Application reject (AR/CR) | red | retry | The receiver refused it for now: wrong version, processing ID, message type, or the interface is down. |
| ACK timeout | amber | retry | No ACK before the timeout. The message **may** have been processed. |
| Connection closed | amber | retry | The receiver closed the connection without an ACK. |
| Invalid ACK | amber | retry | The response is not an HL7 ACK (no MSA segment, not MLLP-framed, HTML, ...). |
| Control ID mismatch | amber | retry | MSA-2 does not match the message's MSH-10. |
| Connection failed | red | retry | Nothing is listening, a firewall blocks the port, or DNS fails. |
| Protocol error / send failed | red | retry | MLLP framing broken, or the connection dropped while sending. |
| Validation failed | red | dead-letter | The message failed the destination's validation level and was not sent. |

Amber outcomes mean the delivery state is unknown. Retrying can deliver a message twice, so receivers should
de-duplicate on MSH-10. What happens after AR, AE and a timeout (retry or dead-letter) can be changed per
destination in the destination editor.

## Common ACK problems

**AE with an ERR segment.** Read the ERR segment in the attempt details: ERR-3 is the error code (HL7 table
0357), ERR-2 the location (segment^sequence^field) and ERR-8 the receiver's text. Typical causes:
- `101` required field missing, `102` data type error, `103` table value not found: fix the message or the
  template. Run **Validate** (or `hl7send validate`) with the *Strict* level to find these before sending.
- `200` unsupported message type or `201` unsupported event code: the receiver's channel does not accept this
  trigger event. Check MSH-9.
- `203` unsupported version ID: MSH-12 does not match what the interface expects.
- `207` application internal error: a problem inside the receiver (Mirth transformer exception, database
  error). Check the receiver's logs; the ERR text often names the failing step.

**AR on every message.** Check MSH-11 (processing ID, `P`/`T`/`D`) and MSH-5/MSH-6 (receiving application and
facility). Many EHR interfaces reject messages for another facility or environment.

**ACK timeout although the receiver got the message.** The receiver took longer than the ACK timeout (30
seconds by default). Increase it on the destination. Mirth channels that respond *after* all destinations run
can be slow; consider *Auto-generate (After source transformer)*. For EHRs, ask the vendor for their expected
response time.

**ACK timeout, and the receiver shows nothing.** The receiver may expect a different framing. MLLP wraps each
message in `0x0B ... 0x1C 0x0D`. Check that the receiving port is an MLLP listener, not raw TCP or HTTP.

**Apache NiFi never acknowledges.** `ListenTCP` does not send ACKs. Clear **Wait for ACK** (`--no-ack`), or build
a flow that returns an MLLP ACK (for example with a scripted processor).

**Control ID mismatch.** The receiver echoes a different MSH-10 in MSA-2, or the connection carried a late ACK
from an earlier timed-out message. The sender closes the connection after any timeout or mismatch so a stray
ACK cannot be matched to the next message. A receiver that always puts its own ID in MSA-2 is misconfigured;
fix its ACK so MSA-2 echoes the message's MSH-10 (in Mirth, an auto-generated response does this).

**Invalid ACK.** Open the attempt and look at the raw response. HTML means an HTTP port; a response without
MLLP framing means a raw TCP listener; a message without MSA is not an acknowledgment. Enhanced mode (MSH-15/16)
receivers may send a commit ACK (CA) first; that is accepted.

**Messages pile up in RETRY_PENDING and the destination shows "circuit open".** After 5 consecutive failures
the circuit breaker pauses the destination for 60 seconds, then tries one message. Fix the cause (receiver
down, firewall); delivery resumes with the next probe. **Retry now** sends the next message at once.

**Dead letters.** Fix the message or the receiver, then **Requeue** (`hl7send dlq requeue ID` or `--all -d NAME`).
Messages dead-lettered after the retry limit (10 attempts by default) can be requeued unchanged once the
receiver is back.

## TLS handshake failures

The attempt error includes the Java TLS message. The common ones:

| Error text contains | Cause | Fix |
|---|---|---|
| `PKIX path building failed`, `unable to find valid certification path` | The server's certificate is not signed by a CA you trust. | Set a **trust store** on the destination: a PEM, PKCS12 or JKS file with the server's CA (or its self-signed certificate). The `openssl` command below shows the chain the server sends. |
| `No subject alternative names matching IP address`, `No name matching` | The certificate does not name the host you connect to. | Connect using a name that is in the certificate, or ask for a certificate with that name/IP. Turning off **Verify that the server certificate matches the host** works but removes protection against impersonation; do it only on test systems. |
| `Received fatal alert: certificate_required`, `bad_certificate` | The server requires a client certificate (mutual TLS). | Set a **key store** (PKCS12 or JKS) with your client certificate and key, and its password. |
| `Received fatal alert: handshake_failure`, `protocol_version`, `No appropriate protocol` | No common TLS version or cipher. | The default is TLS 1.3 and 1.2. Old receivers may offer only TLS 1.0/1.1, which Java disables; upgrade the receiver. |
| `certificate_expired`, `NotAfter` | The server's (or your) certificate expired. | Renew it. **Check certificates** in the destination editor lists your stores' expiry dates; turn on the certificate alert in **Tools > Alerts and notifications** to be warned 30 days ahead. |
| `Keystore was tampered with, or password was incorrect` | Wrong key or trust store password. | Re-enter it in the destination editor. Passwords are kept in the OS keychain. |
| `Unsupported record version`, `plaintext connection?` | TLS is on, but the port speaks plain MLLP (or the reverse). | Match the destination's **TLS** setting to the receiver port. |

To inspect the server from a terminal:

```bash
openssl s_client -connect host:port -servername host -showcerts </dev/null
```

## The app or CLI

**"Queue unavailable" (exit code 7), or the Queue tab is disabled.** Another process (an app window or
`hl7send serve`) already delivers from this queue, or the queue is encrypted and its key is not available
(for example in a service running as another user). Close the other window, or use the running process; CLI
commands still work alongside it.

**Saved passwords are missing after moving to another computer.** Passwords live in the OS keychain, not in
exported destination profiles. Re-enter them. **Help > Security** shows where secrets are kept.

**Linux: no tray/desktop notifications, or no keychain.** Headless servers and some desktops have no Secret
Service; the app then keeps secrets encrypted in `secrets.json` next to the settings.

**macOS: "cannot be opened because the developer cannot be verified".** The build is not notarized (see the
release notes). Right-click the app, choose **Open**, then confirm; or run
`xattr -dr com.apple.quarantine "/Applications/HL7 Sender.app"`.

**Windows: SmartScreen warns about an unknown publisher.** Unsigned builds show this. Choose **More info >
Run anyway**, or install a signed release.

**Update check fails.** It needs HTTPS access to `api.github.com`. Behind a proxy, start Java with
`-Dhttps.proxyHost=... -Dhttps.proxyPort=...`. The check is optional; nothing else needs the internet.

## Shared database, users, scripts and plugins

**"Cannot open the PostgreSQL queue database: Connection ... refused" or "... timed out".** The server is not
reachable from this computer: check the host and port in **Tools > Queue database...** (or
`hl7send database show`), firewalls, and that PostgreSQL listens on the network (`listen_addresses`,
`pg_hba.conf`). `hl7send database test` retries the connection and reports the error.

**"password authentication failed".** The stored password is wrong or missing. Enter it again in **Tools > Queue
database...**, or run `hl7send database use-postgres URL --db-user NAME` again.

**Another computer's app shows the queue but nothing is sent.** Only one process delivers from a shared queue.
Check that the delivering app or `hl7send serve` is running; if it stopped, the next one to start takes over.
**Tools > Queue database...** says whether this window delivers.

**"This queue has users: sign in with --user NAME" (exit code 11).** Users were added, so queue commands need
`--user NAME` (or `HL7SENDER_USER`) and the password in `HL7SENDER_PASSWORD`. For CI jobs, create an `operator`
user for the pipeline and store its password as a CI secret.

**"Sign-in failed".** Wrong user name or password, or the user is disabled. Failed sign-ins are recorded in the
audit trail. An administrator can set a new password (`hl7send user password NAME`). If the only administrator's
password is lost, someone with access to the database must remove the user row (`DELETE FROM app_user WHERE
username = '...'`); removing every user turns sign-in off.

**"... may not ..." (exit code 11), or a button is missing in the app.** The user's role does not allow it: see
the roles in the [user guide](user-guide.md#signing-in). Role changes apply at the next sign-in.

**A message was not queued: "Filtered out by the destination's script", or "Script error on line N" / "Syntax
error on line N".** The destination's transform
script filtered the message out or failed on it. Test the script on the message with the destination editor's
**Script > Test**, or `hl7send script test -d DESTINATION message.hl7`. Scripts that run for more than one second
per message are stopped.

**A plugin transport is missing, or shows "(plugin not installed)".** The jar is not in the `plugins` folder of
this computer's settings folder, or it failed to load. `hl7send destination transports` lists the plugins found and
any errors. Restart the app or service after adding a plugin.
