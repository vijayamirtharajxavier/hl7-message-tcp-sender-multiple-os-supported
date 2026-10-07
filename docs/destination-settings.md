# Destination settings by transport

A destination's **Transport** tab sets how its messages are sent: **MLLP** (the default), **HTTP(S)**,
**File (folder)** or **FHIR R4**. Most settings in the destination editor apply to every transport, some only to
one. This page lists which settings each transport uses and what each one means for it.

Some settings that a transport does not use stay editable in the dialog. HL7 Sender ignores them for that
transport, so you can leave them at their defaults.

## Connection and delivery tab

| Setting | MLLP | HTTP(S) | File (folder) | FHIR R4 |
|---|---|---|---|---|
| Host, Port | Receiver's address | Not used (greyed out); the URL replaces them | Not used (greyed out) | Not used (greyed out); the base URL replaces them |
| Connection (persistent or per message) | Used | Not used (greyed out) | Not used (greyed out) | Not used (greyed out) |
| Connect timeout (ms) | Opening the TCP connection | Opening the HTTP(S) connection | Not used | Opening the HTTP(S) connection |
| ACK timeout (ms) | Waiting for the ACK, as a total deadline | Waiting for the HTTP response | Not used | Waiting for the HTTP response |
| Charset | Encoding of the message and the ACK | Encoding of the request and response body, and the default Content-Type | Encoding of the written file | Not used: FHIR JSON is always UTF-8 |
| Wait for ACK | Off: complete each message as soon as it is written, without reading an ACK | Off: a 2xx response completes the message as `SENT_UNCONFIRMED` without reading the body | Not used: a written file is always `SENT_UNCONFIRMED` | Off: a 2xx response completes the message as `SENT_UNCONFIRMED` |

## Retry and circuit breaker

These work the same for every transport.

| Setting | Meaning |
|---|---|
| Max attempts | Attempts before a message that keeps failing goes to the dead-letter queue. 0 retries forever. |
| First delay, Max delay, Jitter | Backoff between attempts: it starts at the first delay, doubles up to the max delay, and varies by the jitter percentage. Every retry sends the same MSH-10. |
| Open after failures, Cool-down | After this many consecutive failures without a usable response (timeouts, connection or write failures, invalid or mismatched ACKs), delivery pauses for the cool-down, then tries one message. 0 turns the circuit breaker off. |

## Acknowledgment policy

For each outcome, choose **Retry** or **Dead-letter**. Which outcomes a transport can produce:

| Setting | MLLP | HTTP(S) | File (folder) | FHIR R4 |
|---|---|---|---|---|
| On AR (reject) | ACK code AR | ACK code AR in the response body; otherwise HTTP 408, 429 or 5xx | Not used | HTTP 408, 429 or 5xx |
| On AE (error) | ACK code AE | ACK code AE in the response body; otherwise any other non-2xx status | Not used | Any other 4xx; a failed entry in the transaction response; a message that cannot be converted to FHIR |
| On CR (commit reject) | ACK code CR | ACK code CR in the response body | Not used | Not used |
| On CE (commit error) | ACK code CE | ACK code CE in the response body | Not used | Not used |
| On timeout / no ACK | No ACK in time; connection closed without an ACK; a response that is not an ACK; an ACK for another message (MSA-2 is not the MSH-10) | No response in time; an ACK in the body for another message | Not used | No response in time |
| Application ACK port, App ACK timeout | Enhanced mode: after a CA, wait for the AA, AE or AR that MSH-16 asks for (see the user guide). 0 turns it off. | Not used: leave at 0 | Not used: leave at 0 | Not used: leave at 0 |

Some failures are always retried and have no setting in the dialog: the connection cannot be opened, the TLS
handshake fails, the connection breaks while sending, or a file cannot be written. Max attempts still applies to
them.

Defaults: AE and CR go to the dead-letter queue, because resending the same message cannot help. AR, CE and
timeouts are retried.

## Other tabs

| Setting | MLLP | HTTP(S) | File (folder) | FHIR R4 |
|---|---|---|---|---|
| Validation level, Conformance profile | The HL7 v2 message is validated when it is queued | Same | Same | Same: the v2 message is validated before it is converted |
| Max messages/second | Used | Used | Used | Used |
| Watch folder | Used | Used | Used | Used |
| Script | Runs on each message as it is queued | Same | Same | Same: runs on the v2 message before it is converted |
| TLS | MLLP over TLS, with an optional client certificate | Optional for `https://` URLs: a custom trust store or a client certificate. Servers with a publicly trusted certificate work without it. | Not used | Same as HTTP(S) |
| Notes | Free text | Free text | Free text | Free text |

## Transport tab fields

### MLLP

Uses the host, port and connection settings on the first tab. Nothing to set here.

### HTTP(S)

| Field | Meaning |
|---|---|
| URL | Where each message is sent, starting with `http://` or `https://`. For example a Mirth HTTP Listener (`http://mirth-host:8081/hl7`) or a NiFi ListenHTTP processor (`http://nifi-host:9090/contentListener`). |
| Method | `POST` (default) or `PUT`. |
| Content type | Sent as `Content-Type`. Default: `application/hl7-v2; charset=` plus the destination's charset. |
| Headers | One per line, for example `Authorization: Bearer <token>`. |

The body is the HL7 v2 message as it would be sent over MLLP. If the response body is an HL7 ACK, its code and
MSA-2 decide the outcome, as for MLLP. Otherwise the HTTP status does.

### File (folder)

| Field | Meaning |
|---|---|
| Folder | Where message files are written, for example a folder an interface engine reads, or a mounted SFTP or SMB share. |
| File name | Pattern with `{CONTROL_ID}`, `{TYPE}` and `{TIMESTAMP}`. Default: `{TIMESTAMP}-{CONTROL_ID}.hl7`. A name that already exists gets `-2`, `-3` and so on. |
| Line ending | `CR` (HL7 standard, default), `CRLF` or `LF`. |

Each file is written under a temporary name and then renamed, so a reader never sees half a message. There is
no acknowledgment: a written file completes the message as `SENT_UNCONFIRMED`.

### FHIR R4

| Field | Meaning |
|---|---|
| FHIR base URL | Where the transaction Bundle is posted. A FHIR server's base URL (for example `http://localhost:8080/fhir` for HAPI FHIR), or an HTTP endpoint in Mirth (HTTP Listener) or NiFi (ListenHTTP) that accepts the FHIR JSON. |
| Headers | One per line, for example `Authorization: Bearer <token>`. |

Each HL7 v2 message is converted to a FHIR R4 transaction Bundle, the same one the Sender tab's FHIR R4 panel
shows, and posted as `application/fhir+json`. A 2xx response is accepted, unless it is a transaction response
with a failed entry. The conversion covers the common segments of the HL7 v2-to-FHIR mappings; it is meant for
testing, previews and simple feeds.

## Which transport to choose

| The receiver expects | Use |
|---|---|
| HL7 v2 over TCP (most EHR, Mirth and NiFi interfaces) | MLLP |
| HL7 v2 in an HTTP request (Mirth HTTP Listener, NiFi ListenHTTP, a REST endpoint) | HTTP(S) |
| HL7 v2 files in a folder | File (folder) |
| FHIR R4 resources (a FHIR server, or an endpoint that takes FHIR JSON) | FHIR R4 |

Plugins from the plugins folder add more transports; see the administrator guide. Which of the settings above
a plugin uses depends on the plugin.
