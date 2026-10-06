# Example transport plugin

A transport plugin lets destinations send messages somewhere other than MLLP, HTTP or a folder: SFTP, a message
broker, a cloud queue. This example prints messages to standard output.

A plugin is a jar with:

- a class implementing `io.hl7sender.core.transport.TransportFactory` (its id, display name, settings, and a
  `Transport` that sends one message and returns a `SendResult`), and
- `META-INF/services/io.hl7sender.core.transport.TransportFactory` naming that class.

## Build

Compile against the app's `core` jar (in an installed app it is in the `lib` or `app` folder; from source, run
`./gradlew :core:jar` and use `core/build/libs/core-*.jar`):

```bash
cd examples/transport-plugin
javac --release 21 -cp ../../core/build/libs/core-1.0.0-SNAPSHOT.jar -d build src/com/example/hl7plugin/*.java
cp -r src/META-INF build/
jar --create --file stdout-transport.jar -C build .
```

## Install

Copy the jar into the `plugins` folder inside the settings folder (for example `~/.config/hl7sender/plugins` on
Linux, `%APPDATA%\HL7Sender\plugins` on Windows) and restart the app or `hl7send serve`. `hl7send destination
transports` lists what was loaded and any problems. The new transport then appears under **Send by** on the
destination editor's Transport tab.

Plugins run inside the app with its permissions, so install only plugins you trust.
