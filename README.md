# CrashGuard

Gemeinsame Crash-Diagnose und Reconnect-Hilfe für Paper, Folia, Velocity und BungeeCord. Java 21 ist erforderlich. Dieses Repository enthält getrennte installierbare Plugins und eine gemeinsame Bibliothek. Paper und Folia verwenden dieselbe Backend-JAR.

## Installation und Build

Mit Java 21 und Maven 3.9+: `mvn clean verify`, alternativ `./build.ps1 -JavaHome <JDK-Verzeichnis> -Maven <mvn.cmd>`.

| Plattform | Plugin-JAR | Compile-API |
| --- | --- | --- |
| Paper | `paper/target/crashguard-paper-1.0.0-SNAPSHOT.jar` | Paper 1.21.4 |
| Folia | `paper/target/crashguard-paper-1.0.0-SNAPSHOT.jar` | Gemeinsame Paper/Folia-Scheduler-API 1.21.4 |
| Velocity | `velocity/target/crashguard-velocity-1.0.0-SNAPSHOT.jar` | Velocity 3.4 |
| BungeeCord | `bungeecord/target/crashguard-bungeecord-1.0.0-SNAPSHOT.jar` | BungeeCord 1.21 |

Die passende JAR in den jeweiligen `plugins`-Ordner legen und neu starten. Im Netzwerk das Backend-Plugin auf jedem Paper-/Folia-Server und genau das passende Proxy-Plugin installieren. Die `original-*.jar` und die Common-JAR nicht installieren. Die APIs werden nicht mitgeliefert; SnakeYAML wird isoliert in jede Plattform-JAR eingebaut. Ältere Minecraft-Versionen sowie reines Spigot/Bukkit werden nicht unterstützt. Die genannten APIs wurden kompiliert; reale Serverintegration ist separat zu prüfen. Andere Paper-Forks können kompatibel sein, sind aber nicht pauschal getestet.

Jede Installation erstellt ihre eigene `config.yml` im Plugin-Datenverzeichnis. Die Vorlage liegt in `common/src/main/resources/crashguard-default.yml`. Einstellungen werden beim Start geladen. Ungültige bekannte Typen und Grenzwerte verhindern den Start. Änderungen erfordern einen Neustart; Plugin-Hot-Reload wird nicht empfohlen.

## Funktionen

* **Paper-Watchdog:** Ein unabhängiger Thread prüft einen pro Tick aktualisierten Heartbeat. Pro Freeze wird ein Bericht erstellt. Nach Erholung kann ein neuer Freeze wieder gemeldet werden. JVM-Monitor-/Synchronizer-Deadlocks werden ebenfalls erfasst.
* **Folia-Watchdog:** Der globale Region-Scheduler liefert den globalen Heartbeat. Zusätzlich werden Spieler über ihren Entity-Scheduler beobachtet (`watchdog.folia.player-regions`). Ein gesunder Bereich kann einen eingefrorenen Spielerbereich nicht verdecken. Einmal pro Vorfall wird berichtet, bis alle beobachteten Quellen wieder laufen oder entfernt wurden. Das überwacht Bereiche mit Spielern; leere Regionen und Regionen ohne erfasste Spieler sind nicht vollständig abgedeckt. Bei Teleports wird die alte Beobachtung ausgesetzt und auf dem nächsten Entity-Tick neu aufgenommen, damit Regionwechsel keine künstlichen Hänger erzeugen.
* **Startphase:** `watchdog.startup-grace-seconds` unterdrückt automatische Freeze-/Deadlock-Erkennung während der ersten 60 Sekunden. Das gibt dem Laden der Welten Zeit; lange Starts können eine größere Schonfrist benötigen. Manuelle Berichte und Exception-Erfassung bleiben verfügbar.
* **Proxy-Diagnose:** Velocity und BungeeCord besitzen keinen einzelnen Bukkit-Main-Thread. Gemessen wird dort die Scheduler-Erreichbarkeit; ein einzelner festhängender Netty-Eventloop kann damit nicht zuverlässig erkannt werden.
* **Berichte:** Atomar geschriebene Markdown-Dateien unter `reports`, mit Heap-Werten, Plugin-Versionen, allen Threadstacks, Deadlock-IDs und zuletzt beobachtetem Kontext. Die Anzahl wird begrenzt. `/crashguard report` fordert einen Bericht an, `/crashguard status` zeigt den Zustand. Beide benötigen `crashguard.admin`.
* **Crash-Kontext:** Paper/Folia erfassen Spielerpositionen und Ping auf dem jeweiligen Entity-Thread. Der globale Scheduler fasst nur unveränderliche Text-Snapshots zusammen und liest keine fremden Spielerpositionen. Die letzten konfigurierten Snapshots werden regelmäßig als `context-journal.txt` gesichert. Ein fehlender sauberer Shutdown wird beim nächsten Start über `running.marker` erkannt. Falls vorhanden, wird ein seit dem vorherigen Start entstandener nativer Minecraft-Crashreport bis 96 KiB eingelesen. Entity-Typ, Position und Welt werden ausschließlich aus vorhandenen Report-Feldern extrahiert.
* **Exception-Erfassung:** Paper und BungeeCord erfassen JUL-SEVERE-Meldungen mit Throwable, begrenzen wiederholte Meldungen durch einen Cooldown und nehmen Ursachenketten auf. Meldungen, die nicht über JUL laufen, werden nicht automatisch abgefangen. Velocity hat keinen globalen SLF4J-Hook in diesem Plugin; dort erfolgen Diagnose und native Report-Übernahme, keine allgemeine Log-Interception.
* **Plugin-Hinweise:** Erfasste Exception-Stacks/native Crashreports und der Heartbeat-Thread werden mit den Packages der Plugin-Hauptklassen abgeglichen. Andere laufende Threads werden nicht zur Schuldvermutung genutzt. Das ist eine Heuristik: gemeinsame Packages, ausgelagerte Bibliotheken und indirekte Aufrufe können falsche oder fehlende Kandidaten liefern. Die Berichte nennen keine garantierte Schuldzuweisung.
* **Discord:** Optionaler Embed mit Diagnosegrund und extrahierten Hinweisen. Aktivierung über `reports.discord.enabled` und `webhook-url`. Keine automatischen Mentions; zusätzliche beobachtete Spielerinformationen sind separat opt-in. Netzwerkzugriffe haben Timeouts und laufen getrennt vom Watchdog. Bei Fehlern bleibt der lokale Bericht erhalten; fehlgeschlagene Webhooks werden nicht erneut gesendet.

## Session Recovery

Paper prüft den `PlayerQuitEvent.QuitReason` beziehungsweise eine explizite `PlayerKickEvent.Cause.TIMEOUT`. Standardmäßig werden nur Timeouts für 15 Sekunden gepuffert. Normales Verlassen, doppelte Logins, Bans, Plugin-Kicks und nicht eindeutig zugeordnete Verbindungs-/Protokollfehler erzeugen keine Recovery. Ein Reconnect selbst ist kein Fehler und erzeugt keinen Crashbericht.

Optional kann `recovery.recover-ambiguous-disconnects: true` auch uneindeutige `DISCONNECTED`-Abmeldungen berücksichtigen. Dazu müssen mehrere aufeinanderfolgende aktuelle Ping-Messungen auffällig sein: standardmäßig drei Messungen über 600 ms oder ein Anstieg gegenüber dem früheren Median um mindestens 300 ms und Faktor drei. `recovery.latency.enabled` muss dafür aktiv sein. Einzelne Spitzen, veraltete Messungen und sporadische Messungen nach einem Freeze reichen nicht. Ping ist nur eine Heuristik und kann absichtliches Verlassen mit schlechter Verbindung nicht sicher unterscheiden; die Option bleibt deshalb standardmäßig deaktiviert. Explizite Timeouts benötigen keinen hohen gemessenen Ping, weil dessen letzter Wert bei einem plötzlichen Verbindungsabbruch noch normal sein kann.

`recovery.respect-other-plugins: true` lässt Spawn-/Login-Teleports vorgehen: Ein Teleport vor dem geplanten Restore verwirft die Recovery, und eine bereits abweichende Loginposition (andere Welt oder mehr als ein Block Unterschied) wird nicht überschrieben. Auch eine Positionsänderung zwischen Join-Event und Restore wird berücksichtigt. Der Snapshot wird dabei verbraucht, nicht später erneut angewendet. Mit `recovery.log-decisions: true` werden Grund, letzter Ping, Latenzhinweis und Pufferentscheidung als INFO ausgegeben, ohne Discord-Bericht.

Derselbe Spieler kann einen Snapshot genau einmal verbrauchen. Der Puffer hat eine feste Kapazität und verwirft abgelaufene beziehungsweise älteste Einträge. Bei erfolgreicher Recovery werden die Fallhöhe zurückgesetzt und für drei Sekunden Schaden in beide Richtungen verhindert. Teleport-Abbruch durch andere Plugins wird respektiert. Tote Spieler sind ausgeschlossen. `crashguard.recovery` ist standardmäßig erlaubt.

Vanilla und die jeweiligen Spielplugins bleiben für Inventar, Gesundheit und Minigame-Status verantwortlich. Ein Zurückschreiben alter Inventare würde Duplikationen ermöglichen und findet daher nicht statt. Integrationen können über Bukkit `ServicesManager` einen `RecoveryPolicy`-Provider registrieren, der Spieler im Kampf oder in inkompatiblen Spielzuständen ausschließt. Jeder registrierte Provider darf die Wiederherstellung ablehnen. Ohne solche Integration ist kein universeller Combat- oder Minigame-Schutz möglich.

Velocity/BungeeCord puffern den Namen des letzten Backends und wählen es bei einem rechtzeitigen neuen Proxy-Login erneut, sofern es noch registriert ist. Backend-Kicks werden nach Möglichkeit ausgenommen; normale Zulassungs-, Ban- und Authentifizierungsprüfungen laufen weiter. Ein nicht erreichbares Backend kann den Verbindungsversuch weiterhin scheitern lassen. Die Proxy-Plugins restaurieren keine Weltzustände. Die Session-Puffer sind im Speicher und überleben keinen Prozess-Neustart.

**Eine abgerissene TCP-Verbindung kann ein serverseitiges Plugin nicht nahtlos erhalten.** Der Charakter bleibt nach einem echten Logout nicht als Vanilla-Spieler im Server. Der Client muss erneut verbinden und lädt die Welt erneut. Unsichtbare Reconnects ohne Ladebildschirm benötigen zusätzliche Client-/Protokoll-Unterstützung. Auch ein vollständiger Minigame-Session-Erhalt benötigt die API des jeweiligen Minigame-Plugins.

## Isolierung

Standardmäßig aus. Mit `isolation.enabled: true` und expliziten `entity-uuids` werden geladene Nicht-Spieler-Entities per deaktivierter Gravitation und gegebenenfalls AI beruhigt. Das Plugin startet vor dem Weltladen (`load: STARTUP`) und verarbeitet Entity-Add-/Chunk-Load-Ereignisse auf der jeweiligen Region. Originalwerte werden vor dem Eingriff im PersistentDataContainer der Entity vermerkt und bei regulärem Chunk-Unload wiederhergestellt. Beim nächsten Entity-Load werden verbliebene Originalwerte zuerst restauriert, auch wenn Isolierung inzwischen deaktiviert ist; danach wird gegebenenfalls erneut isoliert. Paper restauriert zusätzlich beim Plugin-Shutdown. Folia greift beim globalen Shutdown nicht auf fremde Entities zu und nutzt stattdessen Chunk-Unload beziehungsweise den nächsten Entity-Load. Vor Deinstallation daher Isolierung deaktivieren, Server neu starten und betroffene Entities laden. Änderungen anderer Plugins während der Isolierung können überschrieben werden. Ein harter Abbruch vor der Speicherung bleibt durch das Plugin nicht abgesichert.

Das ist keine vollständige Entity-Tick-Sperre. ArmorStands besitzen beispielsweise keine abschaltbare Mob-AI. Es werden keine Chunks gelöscht, keine Entities entfernt und keine UUIDs automatisch aus bloßen Stacktrace-Vermutungen auf eine Sperrliste gesetzt. Crashs während der Deserialisierung können bereits vor einem Chunk-Load-Event passieren und benötigen Offline-Reparatur beziehungsweise ein Backup.

## Neustarts und Backups

`watchdog.action: report` ist der Standard. `shutdown` fordert einmal den regulären Plattform-Shutdown über den globalen Scheduler an. Ist dessen Thread blockiert, kann auch der Shutdown blockieren. Ein externer Prozessmanager muss harte Hänger erkennen und gegebenenfalls den Prozess neu starten. Das Plugin startet keinen zweiten JVM-Prozess und führt keine Shellbefehle aus. Ein pausierter leerer Server kann ebenfalls keinen Tick-Heartbeat senden: Für den Watchdog muss `pause-when-empty-seconds` in `server.properties` deaktiviert sein (`-1`), sofern die Serverversion diese Funktion anbietet.

Das Kontextjournal ist ein Diagnose-Backup, **kein Welt-/Inventar-Backup**. Eine sichere Speicherung einer Welt während eines blockierten Main-Threads ist mit der Plugin-API nicht garantierbar. Vollständiges Out-of-Memory, JVM-Absturz oder Betriebssystem-Kill können auch die Diagnose verhindern; vorhandene Journale/native Crashreports werden beim nächsten Start genutzt. Für Weltdaten sind reguläre Backups und ein externer Supervisor erforderlich. Das Plugin verhindert nicht beliebige Exceptions oder StackOverflows.

## Prüfung auf einem Testserver

1. Plugin starten, generierte Config prüfen und `/crashguard status` ausführen.
2. `/crashguard report`: lokale Datei und optional Discord-Embed kontrollieren.
3. Normal ausloggen und neu verbinden: keine Recovery und kein Rückteleport. Einen tatsächlichen Timeout simulieren und innerhalb/außerhalb von 15 Sekunden neu verbinden; mit Spawn-Plugin prüfen, dass dessen Ziel Vorrang hat. Optional Entscheidungen mit `recovery.log-decisions` kontrollieren.
4. Backend-Kick/Ban testen: keine Rückführung an Sperren vorbei.
5. Isolierung mit einer Test-Mob-UUID aktivieren; Laden/Entladen und regulären Shutdown prüfen.
6. Freeze nur in einer Wegwerf-Testinstanz simulieren; Bericht, Erholung und externe Neustartregeln prüfen.

Automatisierte Common-Tests decken Ablaufgrenze, Einmalverbrauch, Kapazität, JSON-Escaping, Berichtserstellung, Neustartmarker, Konfigurationsvalidierung, Crashreport-Extraktion und unabhängige Region-Heartbeats einschließlich Bericht/Erholung ab. Folia verwendet ausschließlich Global-/Entity-Scheduler und `teleportAsync`; nach dem Teleport werden Spielerwerte erneut auf dem Entity-Scheduler gesetzt. Externe `RecoveryPolicy`-Provider müssen ebenfalls auf Folia threadsicher sein und dürfen nur Daten aus der zuständigen Region lesen. Die Umsetzung folgt den [PaperMC-Vorgaben für Paper/Folia](https://docs.papermc.io/paper/dev/folia-support/). Kein Live-Server-Test wird durch einen erfolgreichen Maven-Build ersetzt; insbesondere Folia-Mehrregionenbetrieb, Teleports und Entity-Isolierung benötigen einen Test auf der eingesetzten Serverversion.
