# User guide

HL7 Sender sends HL7 v2 messages to a receiver (an EHR interface, Mirth Connect, Apache NiFi, ...) over
TCP/MLLP, waits for the acknowledgment, and keeps unacknowledged messages in a durable queue until they are
delivered. This guide covers the desktop app; the [README](../README.md#command-line-hl7send) covers `hl7send`.

## Install

Download the installer for your system from the
[Releases page](https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/releases). Each release lists
SHA-256 checksums.

| System | File | Notes |
|---|---|---|
| Windows 10/11 (x64) | `.msi` | Installs for the current user; no administrator rights needed. Start menu entry *HL7 Sender*. |
| macOS 14+ (Apple silicon) | `.dmg` or `.pkg` | Drag the app to Applications. |
| Debian / Ubuntu 22.04+ | `.deb` | `sudo apt install ./hl7-sender_*_amd64.deb` |
| Fedora / RHEL | `.rpm` | `sudo dnf install ./hl7-sender-*.x86_64.rpm` |
| Other Linux (x64) | `.AppImage` or `-linux-x64.tar.gz` | No install: make the AppImage executable and run it. |

Installers include their own Java runtime. The command-line tool `hl7send` is installed alongside the app:

- **Windows**: `hl7send.exe` in the install folder, usually `%LOCALAPPDATA%\HL7 Sender` (add it to `PATH`).
- **macOS**: `/Applications/HL7 Sender.app/Contents/MacOS/hl7send`.
- **Linux packages**: `/usr/bin/hl7send`. **AppImage**: `./HL7_Sender-*.AppImage hl7send --help`.

If a release is not signed, Windows SmartScreen and macOS Gatekeeper warn on first start; see
[Troubleshooting](troubleshooting.md#the-app-or-cli).

## First start

The **welcome wizard** creates your first destination: enter the receiver's host and port (or choose the
built-in test listener), click **Test connection**, and send a sample message. You can run it again from
**Tools > Welcome wizard...**.

## Sending a single message

1. On the **Sender** tab, paste a message, open a `.hl7` file (**File > Open message...**) or pick a sample.
   The editor highlights segments and shows the field under the cursor, for example
   `PID-5.1 Patient Name > Family Name`.
2. Set the host and port, or choose a saved destination.
3. Click **Validate** to check the structure and data types, then **Send** (Ctrl+Enter).
4. The response panel shows MSA-1 (the ACK code), MSA-2 (the control ID it acknowledges), any ERR segments,
   the raw ACK, the message as sent and the round-trip time.

A direct send is sent once and not retried. To get retries and a record in History, use **Add to queue**.

## Destinations and the queue

A **destination** is a saved receiver with its own queue and delivery rules. Create one on the **Queue** tab
with **Add...**, or from a preset (**More > New from preset**: Mirth Connect, Apache NiFi, EHR, test listener).

Settings per destination:

- **Connection**: host, port, connect timeout, ACK timeout (a total deadline), character set, persistent or
  per-message connection, and **Wait for ACK** (turn it off only for receivers that never acknowledge).
- **Retries**: attempts (10 by default, or unlimited), backoff from 2 seconds up to 5 minutes with jitter.
- **Circuit breaker**: after 5 consecutive failures, pause for 60 seconds, then try one message.
- **ACK handling**: retry or dead-letter after AR, AE, CR, CE and timeouts. By default AE and CR go to the
  dead-letter queue, because resending the same message cannot help, and AR, CE and timeouts are retried.
- **Application ACKs** (enhanced mode): an **Application ACK port** on which HL7 Sender listens for the
  receiver's application ACKs, and how long to wait for each (see below).
- **Validation**: Lenient, Standard or Strict, plus an optional conformance profile.
- **Rate limit** (messages per second), **folder watch** (see below), **TLS** (see below) and free-text notes.

Messages are written to the queue database before they are sent and are delivered in order, one at a time per
destination, so event order is preserved. Every retry reuses the same MSH-10 so the receiver can de-duplicate.

Message states:

| State | Meaning |
|---|---|
| QUEUED | Waiting to be sent. |
| IN_FLIGHT | Being sent; waiting for the ACK. |
| RETRY_PENDING | The last attempt failed; the next one is scheduled. |
| AWAITING_APP_ACK | Committed by the receiver (CA); waiting for the application ACK that MSH-16 asks for. It does not block the queue. |
| ACKNOWLEDGED | Accepted (AA, or CA when no application ACK is expected). |
| SENT_UNCONFIRMED | Sent to a destination without ACKs. |
| DEAD_LETTER | Given up: an AE or CR, an AE or AR application ACK, no application ACK in time, the retry limit, or moved there by hand. Requeue it after fixing the cause. |

Select a message to see its content, every attempt with its ACK and error, and its audit trail. Use **Pause**
and **Resume** per destination, **Retry now** to skip the backoff, and **Move to dead letter** to unblock a queue
whose first message keeps failing.

## Many messages

- **File > Import messages...** queues files, whole folders, HL7 batch files (FHS/BHS), MLLP captures, or N copies
  of a template. The import shows progress and can be cancelled.
- **Templates** fill in variables per message: `${NOW}`, `${SEQ}`, `${UUID}`, `${RANDOM_MRN}`,
  `${RANDOM_LAST_NAME}` and others (see the README). Use them to generate test data.
- **Folder watch**: files dropped in a destination's watch folder are queued automatically, then moved to
  `processed/` (or `error/` with a report).
- **Fan-out**: queue the same message for several destinations at once.

## TLS

Turn on **Use TLS** in the destination editor. Set a **trust store** if the receiver's certificate is not issued
by a public CA (PEM files work), and a **key store** with your client certificate if the receiver requires mutual
TLS. Passwords are saved in your system's keychain. **Check certificates** lists the certificates and their expiry
dates. If the connection fails, see [TLS handshake failures](troubleshooting.md#tls-handshake-failures).

## Watching delivery

- **Dashboard**: per destination, the state, queue depth, dead letters, throughput, accept/NACK rates and ACK
  latency. **Edit...** on a card changes the destination's settings (for example a mistyped host or port), and
  **Delete...** removes it, with its messages and history, after asking you to confirm.
- **History**: search all messages by destination, status, type, control ID, text, batch and date; export to
  CSV (no content) or JSON.
- **Alerts** (**Tools > Alerts and notifications**): desktop notifications, a webhook (Slack/Teams) or e-mail when
  messages are dead-lettered, a destination stops delivering, or a certificate is about to expire.
- **Logs** tab: the live application log. Message content is never logged.

## Application ACKs (enhanced mode)

In enhanced acknowledgment mode the receiver answers in two steps. First it returns a commit ACK (CA, CE or CR)
on the same connection, meaning it has the message in safe storage. Later, if the message's MSH-16 asks for one,
it sends the application's verdict (AA, AE or AR) as a separate message on a new connection to the sender.

To follow these, set the destination's **Application ACK port** (0 = off) and **App ACK timeout**. HL7 Sender then
listens on that port, and a message answered with CA waits as AWAITING_APP_ACK according to its MSH-16:

| MSH-16 | Application ACK arrives | No application ACK before the timeout |
|---|---|---|
| AL (always) | AA: ACKNOWLEDGED. AE or AR: DEAD_LETTER. | DEAD_LETTER (`APP_ACK_TIMEOUT`) |
| ER (errors only) | AE or AR: DEAD_LETTER. | ACKNOWLEDGED (`APP_ACK_NOT_SENT`): no news is good news |
| SU (success only) | AA: ACKNOWLEDGED. | DEAD_LETTER (`APP_ACK_TIMEOUT`) |
| NE or empty | - | The CA completes the message, as without a port. |

The application ACK is matched to the message by MSA-2 = MSH-10, and is shown in the message's history after the
attempt it answers. A message dead-lettered by an application ACK is not resent automatically, because the
receiver already has it; fix the cause and re-queue it. If an AA arrives after the timeout, it still completes
the message. A waiting message does not block the queue, and **Move to dead letter** stops waiting for it.

Only the destination's own host (any of its addresses) and this computer may connect to the application ACK
port; other connections are refused and recorded in the destination's audit trail. HL7 Sender answers each
application ACK with CA (or AA if it has no MSH-15, and nothing if its MSH-15 is NE), and rejects anything that is
not an acknowledgment. An ACK that matches no waiting message is answered and recorded in the audit trail. Open
the port in the firewall of the computer running HL7 Sender.

## Testing without a receiver

The **Test Listener** tab is a mock receiver. It can accept, return AE or AR, send the wrong control ID or a
malformed response, stay silent, or close the connection, optionally after a delay, and can use TLS. Use it to
see how your destination settings behave before connecting to a real system.

To try application ACKs, tick **Enhanced mode (CA/CE/CR)** and enter the destination's application ACK port in
**App ACK to port**, with the code to send (AA, AE or AR). After each CA, the listener sends that application ACK
to the sender one second later, following the message's MSH-16. `hl7send listen --commit-codes --app-ack-port`
does the same from the command line.

## Two-way simulation (responder rules)

The Test Listener can also play the other system. Under **Responder rules**, add rules that choose the response
by message type and field values; the first matching rule wins, and other messages get the default response.

- **Message type**: `ORM^O01`, `ADT^A0*`, or `ADT` for every ADT event; empty matches anything.
- **Field** and **Field matches**: for example `MSH-4` with the regular expression `(?!HOSP).*` to reject
  unknown facilities. With no expression, the rule matches when the field has a value.
- **Response**: any listener mode, or a **custom response** template.
- **Follow-up message**: queue a message to a destination after a delay, such as an ORU result for every order.
  Templates copy values from the message being answered with `${IN:PID-3.1}`, `${IN:MSH-10}` or
  `${IN:OBX(2)-5}`, alongside the usual variables.

**Save received messages to** writes every message to a folder, one file each. **Export...** saves the rules as
JSON for `hl7send listen --rules rules.json`. Follow-ups are queued, so the window (or `hl7send serve`) that
delivers from the queue sends them.

## Scheduled sends and replay

**Tools > Scheduled sends...** queues messages on a timetable while the app (or `hl7send serve`) delivers from the
queue. A schedule has a destination, a cron expression (five fields: minute hour day-of-month month day-of-week,
for example `0 7 * * 1-5` for weekdays at 07:00, or `@hourly`), a time zone, and the message or template to queue,
with a number of copies per run. Every run gets new MSH-10 and MSH-7 values. **Run now** queues a run at once. The
table shows each schedule's next and last run.

To send earlier messages again, select them on the **History** tab and choose **Replay...**. Copies go to their
original destination or another one, optionally with new control IDs (otherwise a receiver that de-duplicates on
MSH-10 ignores them). The originals are not changed, and the copies are tagged as a replay batch.

## Transform scripts

A destination can run a JavaScript **script** on each message as it is queued, like a Mirth Connect transformer.
Write it on the destination editor's **Script** tab (**Insert example** gives a starting point) and try it on the
sample message below it with **Test**.

```javascript
msg.set('MSH-6', 'TESTFAC');                 // change a field (PID-5.1, OBX(2)-5, PID-3[2].1 ...)
msg.remove('NK1');                           // remove every NK1 segment
if (msg.type() == 'ADT^A08' && msg.get('PV1-2') == 'O') filter('outpatient updates are not needed');
msg.add('ZHS|1|' + msg.get('PID-3.1'));      // add a segment
log('facility changed for ' + msg.get('PID-3.1'));
```

Also available: `msg.count('OBX')` and `msg.text()`. A filtered message is not queued, and the reason is shown.
Scripts run in a sandbox with no access to files, the network or Java, and each message may take at most one
second, so a mistake cannot stop delivery. Replayed messages and edited dead letters are sent as they are, without
running the script again. `hl7send script test` runs a script from the command line.

## Other transports: HTTP, folder, FHIR and plugins

A destination normally sends over MLLP. On the destination editor's **Transport** tab, **Send by** can instead be:

- **HTTP(S)**: POST (or PUT) each message to a URL, with optional headers. An HL7 ACK in the response body is read
  like an MLLP ACK; otherwise a 2xx status counts as accepted, 408, 429 and 5xx are handled like AR (retried by
  default) and other statuses like AE (dead-lettered by default), following the destination's ACK policy.
- **File (folder)**: write each message to a folder, for systems that pick up files.
- **FHIR R4**: convert each message to a FHIR transaction Bundle and POST it to a FHIR server's base URL.
- A **plugin** from the plugins folder, such as SFTP or a message broker (see the administrator guide).

Queuing, retries, the dead-letter queue, scripts and history work the same for every transport.

## FHIR R4 preview

The Sender tab's **FHIR R4** panel shows the message as a FHIR R4 transaction Bundle as you type: PID becomes a
Patient (created only if no patient has the same identifier), PV1 an Encounter, NK1 RelatedPersons, AL1
AllergyIntolerances, DG1 Conditions, an ORU a DiagnosticReport with Observations, and an ORM or OML a
ServiceRequest. Segments that are not converted are listed. PV1-2 (patient class) I, O, E and P become the
Encounter classes IMP, AMB, EMER and PRENC; any other value, or an empty PV1-2, becomes the null flavor `UNK`
(unknown) with a note naming PV1-2, rather than a guessed class. The conversion covers the common fields of the HL7
v2-to-FHIR mappings; it is meant for previews, test data and simple feeds. `hl7send fhir convert` prints the same
Bundle.

## Load testing

The **Load Test** tab sends messages directly (not queued) over several connections at a target rate, and
reports throughput, ACK latency percentiles (p50, p95, p99, max) and outcomes, with live charts. Choose a saved
destination or an address, the Sender tab's message or message files (templates get new values for every
message), the number of connections, the rate (0 = as fast as the receiver answers), and a message count or
duration. Optional thresholds (maximum error percentage, maximum p95) mark the run as passed or failed.
**Export report...** saves the results as JSON. `hl7send load` does the same from the command line, with an exit
code for CI.

Test against test systems only: a load test can overwhelm a receiver.

## Signing in

If your administrator has added users (**Tools > Users and roles...**), the app asks you to sign in when it
starts, and the status bar shows who is signed in. What you can do depends on your role:

| Role | Can |
|---|---|
| Viewer | See destinations, queues, history, the dashboard and logs. |
| Operator | Also send and queue messages, replay history, run schedules and load tests, and work the queue (retry, requeue, edit, delete, pause). |
| Administrator | Everything, including destinations, schedules, alerts, the local API, the queue database, security settings and users. |

Buttons and menu items your role does not allow are hidden. Queue changes are recorded in the audit trail under
your user name. The command line asks for the same sign-in: `hl7send --user NAME ...` with the password in
`HL7SENDER_PASSWORD`.

## Updates

**Help > Check for updates...** compares your version with the latest release on GitHub and links to its download
page. Tick **Check automatically** to check once a day at startup. Nothing is downloaded or installed
automatically, and only the version request is sent. Installing a newer version keeps your settings and queue.

## Keyboard shortcuts

Ctrl+1 to Ctrl+7 switch tabs, Ctrl+Enter sends, F5 refreshes, and F1 lists all shortcuts. On macOS use Cmd.
