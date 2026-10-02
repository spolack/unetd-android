# Design-Review unetd-android

Stand: 2. Oktober 2026, HEAD `a2950f6`. Upstream-Referenzen: unetd `7c3213d`,
wireguard-go `ecfc5a8`, beide am gepinnten Submodul-Commit gelesen.

Dieses Dokument bewertet sechs Architekturentscheidungen der App. Es ist bewusst
kritisch formuliert. Es ändert nichts; jede Empfehlung ist ein Vorschlag, der
erst nach Freigabe umgesetzt wird. Wo ich eine Aussage nicht aus dem Code oder
aus Upstream belegen konnte, steht das dabei.

## 0. Was nicht verloren gehen darf

Die App funktioniert heute Ende-zu-Ende. Jeder Umbau muss diese Fundstücke
tragen, weil jedes einzelne einen Ausfall gekostet hat. Sie stehen hier als
Register, damit kein späterer Umbau sie stillschweigend aufgibt.

| Nr. | Fundstück | Beleg |
|---|---|---|
| F1 | Eine Adressfamilie, für die die VPN weder Adresse noch Route noch DNS setzt, wird für die App **blockiert**, nicht durchgereicht. `allowFamily` für beide Familien ist Pflicht. | `UnetVpnService.kt:176-184`, Commit d867655, Emulator-Test t2 |
| F2 | Android 17 (API 37) verweigert Pakete an LAN-Adressen mit `EPERM`, bis `ACCESS_LOCAL_NETWORK` erteilt ist. | `AndroidManifest.xml:9-14`, `MainActivity.kt:44-49`, Commit ee0c9c6 |
| F3 | Jeder `listen_port`-Schreibzugriff über UAPI lässt wireguard-go seine Sockets neu öffnen; ein einmaliges `protect()` nach dem Start hält nicht. unetd schreibt `listen_port` bei jedem Wechsel des lokalen Hosts und zweimal pro STUN-Zyklus. `protect()` muss **beim Bind** passieren. | `api-android.go:15-18, 67-74`, `README.md:293-298` |
| F4 | `addDisallowedApplication` ist kein Ersatz für `protect()`: es nähme den per-Netzwerk-PEX-Socket mit aus dem Tunnel, der aber hindurch muss. | `UnetVpnService.kt:186-189` |
| F5 | Ohne `CAP_NET_RAW` brach `pex_open()` komplett ab, und `pex_fd` war 0-initialisiert, sodass PEX-Datagramme auf stdin geschrieben wurden. | Patch 0004, `README.md:267-275` |
| F6 | Jede Host-Adresse ist aus dem Pubkey abgeleitet, das `/64` ist vor jedem Peer bekannt; eine Route deckt alle Peers, Peer-Wechsel berührt den tun nie. Neu etabliert wird nur bei geänderten Adressen/Subnetzen. | `README.md:60-69, 150-160`, M0 |
| F7 | Beim Trennen zuerst den tun schließen, dann unetd stoppen: dessen Loop kann minutenlang in `getaddrinfo()` hängen. | `UnetVpnService.kt:360-365`, Commit 320c42f |
| F8 | Strings aus cgo zeigen auf JNI-Puffer, die nach dem Aufruf freigegeben werden; alles, was Go behält, muss kopiert werden. | `api-android.go:76-90`, Commit 700eed0 |
| F9 | QEMU liefert Emulator-Pakete von 127.0.0.1; `dht.c` verwirft 127/8 als Martian. SNAT auf loopback funktioniert nicht. | `README.md:356-363`, `tests/emulator/udp-proxy.py` |
| F10 | Ein Linux-NAT ohne WAN-`INPUT`-Drop bestätigt Conntrack für unerbetene Pakete und verliert dann den Quellport des LAN-Hosts. | `README.md:461-466`, `nat-testbed.sh:278-286` |
| F11 | Die beiden Upstream-Bootstrap-Router sind tot; `dht.transmissionbt.com` antwortet. Ohne Patch 0011 bootstrapt kein unet-dht nach Reboot. | Patch 0011, Commits 7d52694, 7a213a8 |
| F12 | `option dht '1'` reicht auf dem Router nicht; unet-dht braucht `-N <auth_key>`. | `README.md:96-113`, Commit 909272e |
| F13 | STUN-Sturm: ohne Raw-Socket lief die Zustandsmaschine nach jeder Auth-Port-Antwort wieder in `QUERY_SEND` (etwa 20 Anfragen/s). | Commit 7e929df, Patch 0004 zweiter Hunk |
| F14 | Öffentliche STUN-Server im Emulator melden das Runner-NAT; unetd bietet den Port als Endpoint-Kandidat an, der Tunnel flattert. | Commit ef14e9b, `tests/emulator/stun-server.py` |
| F15 | Auf dem Telefon ist Roaming in wireguard-go abgeschaltet; Peer-Endpoints kommen ausschließlich aus unetds Kandidaten. | `api-android.go:111`, wireguard-go `device/uapi.go` (`disableRoaming` für UAPI-gesetzte Endpoints) |
| F16 | Keepalive 25 statt 10, DHT nur solange nötig, Log aus, Polling folgt dem Bildschirm. | `README.md:366-392`, Commits 853973d, 5ccf635 |
| F17 | Eine nach Roaming neu angelegte Peer-Instanz meldet die Handshake-Zeit als Epoche. | Commit a2950f6 |
| F18 | Verifiziert am 2.10.2026: 5G hinter Carrier-NAT gegen OpenWrt hinter port-restricted NAT, keine Portweiterleitung, DHT-Discovery über das öffentliche BitTorrent-DHT, Handshake auf dem erhaltenen Port 51830. | `README.md:413-418` |

## 1. wireguard-go statt Kernel-WireGuard

### Ist-Zustand

`libwg-go.so` (wireguard-go als c-shared, `native/libwg-go/`) bekommt den tun-fd
aus `VpnService.Builder.establish()` und bedient den UAPI-Socket
`<filesDir>/run/wireguard/<netz>.sock`. unetd ist mit `WG_LINUX_SUPPORT=off`
gebaut (Patch 0001), `wg-user.c` ist das einzige Backend. Der Build
erfordert Go und ein Submodul mit zwei Patches (siehe Abschnitt 4).

### Was für den Kernel spräche

- Durchsatz und CPU-Last pro Paket sind im Kernel besser; wireguard-go kopiert
  jedes Paket durch den tun-fd und den Go-Scheduler.
- Seit dem GKI-Kernel 5.4 (Android 12) ist `CONFIG_WIREGUARD` im Android Common
  Kernel eingeschaltet, der Code ist also auf aktuellen Geräten vorhanden
  ([Phoronix](https://www.phoronix.com/news/WireGuard-Android-GKI-Enabled),
  [kernel/common Commit](https://android.googlesource.com/kernel/common/+/0933807d35a8ce84f16e5bfef17a35e7791efb60)).
  Ob ein konkretes Gerät das Modul geladen hat, muss man prüfen:
  `adb shell cat /sys/module/wireguard/version` oder `adb shell ls /sys/module | grep wireguard`.

### Warum es trotzdem keine Option ist

- Ein `wireguard`-Netdev anlegen und per Generic Netlink konfigurieren braucht
  `CAP_NET_ADMIN`. Eine App bekommt vom System nur einen tun-fd. Es gibt keine
  Android-API, die einer unprivilegierten App ein Kernel-WireGuard-Interface
  gibt. Das ist kein Policy-Detail, das sich umgehen lässt.
- Die offizielle App trifft dieselbe Entscheidung: `WgQuickBackend` nur mit Root
  und `wg-quick`, sonst `GoBackend`
  ([javadoc WgQuickBackend](https://javadoc.io/static/com.wireguard.android/tunnel/1.0.20210211/com/wireguard/android/backend/WgQuickBackend.html)).
- Die gesamte Steuerung dieser App beruht darauf, dass die Datenebene im eigenen
  Prozess liegt: der UAPI-Socket im App-Verzeichnis (F6, M0), `protect()` beim
  Bind (F3) und die in Abschnitt 3 beschriebene Option, den WireGuard-Socket
  selbst für STUN zu nutzen. Mit einem Kernel-Backend wäre jeder dieser Punkte
  neu zu lösen, und `wg-linux.c` bräuchte wieder libnl-tiny und Netlink-Rechte.

### Ein optionales Kernel-Backend für gerootete Geräte?

Nein. Es wäre ein zweiter Codepfad, den die CI nicht testen kann (Emulator ohne
Root-Modul), für eine Zielgruppe, die mit dem Go-Backend bereits bedient ist. Die
offizielle App hat diesen Pfad, weil sie ihn historisch zuerst hatte, nicht weil
er nötig ist.

### Was ich an der heutigen Umsetzung kritisiere

- `wgGetSocketV4/V6` und `wgGetConfig` sind exportiert, durch JNI geschleift und
  nirgends aufgerufen (`api-android.go:188-233`, `jni.c:143-166`,
  `WgGo.kt:27-28`). Der README-Satz, ein `sendto()` auf diesem fd sei „arguably
  better“ als STUN (`README.md:285-289`), beschreibt etwas, das nicht
  implementiert ist und so auch nicht funktionieren würde (Abschnitt 3).
- Die Host-Tests laufen gegen ein zur Bauzeit geklontes, ungepatchtes
  wireguard-go (`scripts/build-host.sh:33-36`), nicht gegen das Submodul mit den
  Patches und nicht gegen die c-shared-Variante. `README.md:459-460` behauptet
  das Gegenteil.
- `wgTurnOn` bekommt immer `""` als Settings; `DisableSomeRoamingForBrokenMobileSemantics`
  wird unbedingt gesetzt. Beides ist richtig, aber nur zusammen mit F15 zu
  verstehen, und das steht nirgends am Aufrufort.

### Empfehlung: **behalten**

wireguard-go bleibt die einzige Datenebene. Konkret ändern: die beiden toten
Exporte entfernen oder durch die in Abschnitt 3 beschriebene echte Nutzung
ersetzen, die README-Aussage korrigieren, Host-Tests gegen dasselbe wireguard-go
bauen wie die App.

## 2. Der Zwei-Prozess-DHT

### Ist-Zustand

`UdhtService` läuft in `:dht` (`AndroidManifest.xml:57-60`). Begründung an vier
Stellen gleichlautend: uloop ist ein prozessweiter Singleton, unetd besetzt ihn
im Hauptprozess. unet-dht besitzt keinen UDP-Socket; es schickt jedes DHT-Paket
über `<filesDir>/run/unetd.sock` an unetd, das es aus dem globalen PEX-Socket
sendet und Antworten über ein per `SCM_RIGHTS` übergebenes Socketpair
zurückreicht (M1b, upstream `udht.c` `udht_open_socket`).

Was dieser Schnitt heute kostet:

- Ein zweiter ART-Prozess mit einer zweiten Kopie von `libunet-android.so`,
  inklusive komplettem unetd, das dort nie läuft (`Udht.kt`, `Unetd.kt` laden
  dieselbe Bibliothek).
- Steuerung per `startService`-Intent alle 15 s (`UnetVpnService.kt:298-336`;
  der Kommentar in `UdhtService.kt:21-22` sagt 30 s). Bei jedem Tick wandert der
  private Schlüssel durch JNI, um den Pubkey zu berechnen (`Zeile 307`).
- Ein Stop-Intent an einen nicht laufenden `:dht`-Prozess **startet ihn**, nur
  um ihn zu beenden; sein `onDestroy` überschreibt dabei die DHT-Logdatei mit
  einem leeren Ring (`UdhtService.kt:76-86`).
- Log-Transport über eine Datei, alle 3 s neu geschrieben (`UdhtService.kt:66-72`,
  `DhtLog.kt`), und im UI alle 2 s auf dem Main-Thread gelesen
  (`AppRoot.kt:34-42`).
- `Process.killProcess(myPid())` in `onDestroy`, weil `udht.c` Dateistatus hält
  und keinen Teardown hat (`UdhtService.kt:83-85`).
- Die Bootstrap-Namensauflösung läuft in `:dht` über den System-Resolver, ohne
  `protect()`; mit Lockdown oder Always-on ist das ein offener Punkt, den
  niemand bisher getestet hat.
- `stop_efd` in `udht_jni.c` wird von zwei Threads ohne Sperre benutzt; ein
  Stop vor Anlegen des eventfd geht verloren (`udht_jni.c:24, 98-124`).

### Die Begründung ist nur halb richtig

uloop ist ein Singleton pro Prozess: `poll_fd`, die Timeout-Liste,
`uloop_cancelled` sind statische Variablen in `uloop.c`. Daraus folgt, dass
**zwei Loops** in einem Prozess unmöglich sind. Daraus folgt nicht, dass
unet-dht einen eigenen Loop braucht. `udht.c` ist vollständig uloop-getrieben:
ein `uloop_fd` (das Socketpair-Ende), vier `uloop_timeout`s, kein blockierender
Aufruf außer `getaddrinfo()` beim Bootstrap, und das macht unetd für Gateways
ebenfalls im Loop. Beide können auf **einem** Loop im selben Thread laufen.
Upstream trennt die Daemons aus Paketierungs- und Rechtegründen, nicht wegen
uloop.

Was dem im Weg steht, und zwar konkret:

- `udht_disconnect()` ruft `uloop_end()`; im gemeinsamen Loop würde das unetd
  stoppen. Ebenso `dht_sendto()` über den `disconnect_timer`. Beides muss im
  Bibliotheksmodus zu „DHT abmelden“ statt „Loop beenden“ werden.
- `udht_main()` parst argv, verbindet sich und ruft `uloop_run()`. Für den
  In-Process-Betrieb braucht es `udht_setup(opts)` / `udht_teardown()`, die den
  Zustand (`bootstrap_peers`, `networks`, `dht_uninit()`, Node-Datei) sauber
  abbauen. Patch 0008 wächst dadurch von 11 auf etwa 100 Zeilen und wird
  Android-spezifischer.
- Die Symbolnamen: `udht.c` definiert `dht_sendto`, `dht_hash`, `dht_random_bytes`,
  `dht_blacklisted` (Callbacks für `dht.c`) als externe Symbole; unetd-core
  definiert keines davon. `static LIST_HEAD(networks)` in `udht.c` kollidiert
  nicht mit unetds `networks`. Das ist geprüft, aber bitte beim Umbau durch
  einen Link-Test absichern.

### Alternative: zwei Prozesse behalten, nur die Warzen beheben

Binder statt Intent-Pings, Log über Binder statt Datei, kein `killProcess`. Das
behält den Speicherpreis und die Doppel-Bibliothek und löst das
Teardown-Problem nicht, es verschiebt es nur.

### Empfehlung: **ersetzen** durch In-Process-Betrieb auf unetds uloop

Der DHT-Knoten wird vom `native/core`-Thread gestartet und gestoppt
(`unetd_core_dht_start/stop` als Kommandos im bestehenden Kanal). Das
Socketpair-Relay bleibt in der ersten Stufe **unverändert**, weil M1b und der
Emulator-DHT-Durchlauf genau diesen Pfad beweisen; nur der Prozess fällt weg.
Erst wenn das läuft, lohnt die Frage, ob das Relay durch direkte Aufrufe
ersetzt wird.

Was dabei bleibt: die Policy „DHT nur solange nötig“ (F16), die Node-Datei, der
`-b`-Bootstrap (F11), der Log im selben Ring wie unetd.
Was wegfällt: `UdhtService`, `:dht`, `DhtLog`, das 15-s-Pingen, der
Prozess-Kill, die doppelte Bibliothek, die ungeschützte DNS-Auflösung.
Risiko: der Teardown in `udht.c`; deshalb M1b als „zweimal starten und stoppen
im selben Prozess“ erweitern, analog zu M1a.

## 3. STUN auf Android: bleibt es, oder reicht `sendto` auf wireguard-gos Socket?

### Wie Upstream STUN macht

`pex-stun.c` kennt drei Sendewege (`network_stun_query_next`):

1. **Übernahme des WireGuard-Ports.** Wenn kein Peer verbunden ist, schreibt
   unetd `listen_port=0` (wireguard-go geht auf einen Zufallsport), bindet selbst
   einen UDP-Socket auf den WireGuard-Port, fragt den STUN-Server und gibt den
   Port am Ende zurück (`network_stun_open_socket`, `network_stun_close_socket`).
   Ergebnis: das echte Mapping des Datenports. **Ohne Raw-Socket.**
2. **Vom PEX-Socket.** Lernt das Mapping des PEX-Ports (`auth_port_ext`).
3. **Raw-Socket mit gefälschtem Quellport** = WireGuard-Port, Antwort auf den
   PEX-Port gelenkt. Lernt den Datenport, während Peers verbunden bleiben.
   Braucht `CAP_NET_RAW`.

Auf Android gibt es Weg 3 nicht. Patch 0004 lässt Weg 2 greifen und geht danach
in `IDLE` (F13). **Weg 1 ist unberührt und läuft bei jedem Verbinden**, weil zu
dem Zeitpunkt kein Peer verbunden ist. Der Emulator-Test t3b sieht genau das.

### Wozu der Datenport gebraucht wird

Die Gegenseite muss das Mapping des Telefon-WireGuard-Ports kennen, bevor der
Handshake des Telefons ein port-restricted NAT auf der Router-Seite passieren
kann. Dafür schickt das Telefon `PORT_NOTIFY`, aber **nur wenn
`stun.port_ext` bekannt ist** (`pex.c`, `network_pex_host_request_update`).
Ohne STUN bleibt nur die Annahme „externer Port = lokaler Port“, die bei
port-erhaltenden NATs stimmt, und am 2.10. stimmte sie (F18, „preserved port
51830“). Bei einem CGNAT, das Ports nicht erhält, ist Weg 1 das Einzige, was
das Telefon noch hat. Zusätzlich: F15 bedeutet, dass das Telefon Peer-Endpoints
nie aus beobachteten Quelladressen lernt. Die Kandidaten von unetd sind alles.

`ENDPOINT_NOTIFY`, der Raw-Socket-Punch vom WireGuard-Port, wird auf Android
nie gesendet (`__pex_msg_send` gibt -1 zurück, nur eine Debug-Zeile). Den
Punch übernimmt faktisch wireguard-gos eigene Handshake-Initiation an jeden
Kandidaten, den unetd einmal pro Sekunde durchrotiert (`host.c`). Das ist
gleichwertig, solange das Telefon den Kandidaten kennt.

### Warum „sendto über wireguard-gos Socket“ STUN **nicht** ersetzt

Ein `sendto()` auf dem fd aus `wgGetSocketV4` schickt die Anfrage vom echten
Datenport, ohne Übernahme und ohne `listen_port`-Schreibzugriffe. Aber die
Antwort landet in wireguard-gos Empfangspfad, der alles verwirft, was keiner
der vier WireGuard-Nachrichtentypen ist. unetd sieht sie nie. Ein `sendto`
allein kann ein NAT-Mapping **öffnen**, aber kein Mapping **lernen**. Es wäre
ein Ersatz für den Raw-Punch (`ENDPOINT_NOTIFY`), der ohnehin durch den
Handshake erledigt wird, und kein Ersatz für STUN.

Was dagegen funktioniert, ist das, was Tailscale in magicsock macht: eine
eigene `conn.Bind`-Implementierung in `libwg-go`, die den UDP-Socket besitzt,
ankommende Pakete am STUN-Magic-Cookie (`0x2112A442` an Offset 4, kein
WireGuard-Typ) erkennt und an C weiterreicht, alles andere an wireguard-go
gibt, und einen `wgSendFromDataPort(dst, bytes)` anbietet. `conn.Bind` ist eine
öffentliche Schnittstelle, dafür ist **kein wireguard-go-Patch** nötig. Auf
unetd-Seite wird daraus ein vierter Sendeweg in `pex-stun.c` hinter dem
Platform-Hook aus Patch 0006, plus ein Einspeisepunkt für die Antwort in den
uloop-Thread. Gewinn: keine Übernahme, keine `listen_port`-Rebinds (F3 wird
entschärft, nicht aufgehoben), STUN-Refresh alle 15 min auch mit verbundenen
Peers, und der Raw-Punch kommt als Nebenprodukt zurück.

### Was ich kritisiere

- Das Repo widerspricht sich darüber, was auf Android gemessen wird: `README.md:330`
  sagt „real data port's mapping“ (richtig), Commit 7e929df, `README.md:278-283`,
  `TunnelEmulatorTest.kt:135-137` und `UnetModels.kt:52` sagen, der Datenport
  könne nie gemessen werden (falsch). Die UI behauptet, der Punch gehe über
  WireGuards Socket (`HomeScreen.kt:260-263`), was nicht implementiert ist.
- t3b akzeptiert Daten- **oder** Auth-Port und kann den Unterschied nicht
  prüfen (`TunnelEmulatorTest.kt:150-152`).
- Der NAT-Testbed läuft als Root (`host-tests.yml`), unetd hat dort Raw-Sockets.
  **Die NAT-Traversierung ist unter Android-Bedingungen (kein `CAP_NET_RAW`)
  nirgends automatisiert getestet.** Der einzige Nachweis ist F18, ein manueller
  Lauf an einem Tag. Das ist die größte Lücke im Testnetz.
- Während der Übernahme (Weg 1) ist der Datenport für ein paar Sekunden tot;
  harmlos beim Verbinden, aber die Übernahme greift auch im `QUERY_WAIT`-Timeout,
  wenn kein Server antwortet, und dann unabhängig vom Peer-Zustand.

### Empfehlung: **behalten**, Begründung korrigieren, Multiplexer als Stufe 2

Jetzt: Doku, Test-Kommentare und UI-Text auf den tatsächlichen Mechanismus
bringen; t3b auf `stun_port_ext` (Datenport) verschärfen; den NAT-Testbed um
einen Lauf mit `capsh --drop=cap_net_raw` auf der Telefonseite erweitern. Das
ist der Test, der diese Entscheidung schützt.

Stufe 2, nur mit Feldbefund (ein Telefon hinter nicht port-erhaltendem NAT, bei
dem F18 nicht reproduziert): der `conn.Bind`-Multiplexer. Ohne diesen Befund ist
er Aufwand ohne nachgewiesenen Nutzen.

## 4. Zwölf Patches, gepflegter Fork oder Upstreaming

### Ist-Zustand

12 Patches auf unetd, 2 auf wireguard-go, als `git am`-Serie auf pristine
Submodul-Commits (`scripts/apply-patches.sh`). Das ist strukturell richtig: die
Serie ist lesbar, jede Änderung hat eine Begründung, der Upstream-Stand ist
jederzeit rekonstruierbar. Ein Fork würde das alles verstecken.

### Triage der unetd-Serie

| Patch | Umfang | Upstream-Chance | Bewertung |
|---|---|---|---|
| 0001 `-Werror`/`WG_LINUX_SUPPORT` optional | CMake | hoch, aber **enthält eine Binärdatei `__pycache__/edits.cpython-311.pyc`** | Vor allem anderen bereinigen. In dieser Form nicht einreichbar. |
| 0002 `__linux__`, `<endian.h>` | 2 Dateien | hoch | Echter Bug (Byte-Order-Vergleich mit undefinierten Makros). Sofort einreichen. |
| 0003 fehlender ifindex tolerieren | 1 Hunk | mittel | Verständlich, aber Upstream wird fragen, warum ein Interface ohne Namen ein Interface ist. Mit Begründung „VXLAN ist der einzige Konsument“ einreichbar. |
| 0004 Raw-Sockets optional + STUN-Idle | pex-msg.c, pex-stun.c | hoch | Betrifft auch Container ohne `CAP_NET_RAW`. Der `pex_fd=-1`-Fix ist ein eigenständiger Bugfix und sollte ein eigener Patch sein. |
| 0005 UAPI-Verzeichnis zur Laufzeit + `snprintf`-Trunkierung | wg-user.c | hoch | Der Trunkierungs-Fix ist unabhängig und gehört separat. |
| 0006 Platform-Hooks | 6 Dateien, +58 | mittel | Sauber, optional, nullinvasiv wenn ungesetzt. Braucht Maintainer-Zustimmung zur Embedding-API. Zwei Lücken: `protect_socket` ist `void`, und `update_cmd` forkt weiterhin nach dem Hook (Abschnitt 5). Mit dem In-Process-DHT (Abschnitt 2) und dem STUN-Multiplexer (Abschnitt 3) wächst er; vorher einreichen. |
| 0007 Status-Dump ohne ubus | ubus.c → network.c, +239 | mittel | Reine Code-Verschiebung plus Export. Upstream hat `UBUS_SUPPORT=off` als Option, also ein legitimer Bug. |
| 0008 udht als Bibliothek | 11 Zeilen | niedrig | Wird durch Abschnitt 2 größer und Android-spezifischer. Lokal halten. |
| 0009 PEX-Diagnose | Debug-Zeilen | hoch | |
| 0010 IPv4-Fallback für den PEX-Socket | pex-msg.c, +165 | hoch | `ipv6.disable=1` kommt auch auf OpenWrt vor. |
| 0011 `-b` und fünf Bootstrap-Router | udht.c | hoch | Betrifft jeden unet-dht-Nutzer (F11). Der Router-Liste-Teil zuerst, allein. |
| 0012 DHT-Relay-Diagnose | Debug-Zeilen | mittel | |

Formales, das jede Einreichung blockiert: alle Patches haben `From: Claude`,
tragen `Co-Authored-By`- und `Claude-Session`-Trailer und kein `Signed-off-by`.
OpenWrt verlangt DCO mit einem echten Namen. In 0001 bis 0005 sind die Trailer
nicht durch eine Leerzeile vom Text getrennt, git erkennt sie also nicht einmal
als Trailer. Die Serie muss vor dem Einreichen umgeschrieben werden (Autor,
Sign-off, Trailer raus). Das ist kein Argument gegen die Serie, nur gegen den
Glauben, sie sei „submittable as is“ (`README.md:239-241`).

Kleinere Punkte in der Serie, die vor dem Einreichen zu klären sind:

- 0010 gibt die umgeschriebene `sockaddr` aus einem statischen Puffer zurück.
- 0011 parst `host:port` mit `strrchr(':')` und `AF_INET`-Hints, kein
  IPv6-Bootstrap; mehr als 16 `-b` werden stillschweigend verworfen.
- 0012 schreibt Peer-Adressen ohne Debug-Schalter nach logcat. Für ein
  Diagnose-Patch bei Upstream in Ordnung, in der App ein Log-Hygiene-Thema.
- 0002 ändert `linux` zu `__linux__` auch um `SO_BINDTODEVICE(network_name)`
  herum. Ob dieser Aufruf auf Android mit einem tun, das nicht nach dem Netz
  heißt, fehlschlägt oder nur ins Leere geht, habe ich nicht verifiziert; ohne
  `CAP_NET_RAW` sollte `setsockopt` mit `EPERM` scheitern und ignoriert werden.
  Prüfen: Log-Screen mit Verbose nach `SO_BINDTODEVICE` oder `EPERM` suchen.

Und zur Mechanik: `apply-patches.sh` macht `git checkout .` und `git clean -fd`
im Submodul und wirft damit lokale Änderungen ohne Rückfrage weg. Kein Build
prüft, ob die Serie angewendet ist; ein lokaler Build auf pristine Submodulen
scheitert erst beim Kompilieren an `platform.h`. Ein CMake-Check auf
`third_party/unetd/platform.h` mit klarer Meldung kostet drei Zeilen.

### Die beiden wireguard-go-Patches sind vermeidbar

- **0001 `AddControlFn`**: Derselbe Effekt ist ohne Patch erreichbar, indem
  `libwg-go` `conn.NewStdNetBind()` in einen eigenen `conn.Bind` einwickelt,
  dessen `Open()` nach dem inneren `Open()` die fds über `PeekLookAtSocketFd4/6`
  holt und `protect()` ruft. Das läuft bei jedem `BindUpdate`, also bei jedem
  `listen_port`-Schreibzugriff, und erfüllt F3. Das Fenster zwischen Bind und
  `protect()` ist dasselbe, das die offizielle App seit Jahren hat
  (`GoBackend` schützt nach `wgTurnOn`); Pakete fließen erst, nachdem `Open()`
  zurückgekehrt ist. Derselbe Wrapper ist später der Platz für den
  STUN-Multiplexer aus Abschnitt 3.
- **0002 `SetSocketDirectory`**: `ipc.UAPIOpen` ist etwa 30 Zeilen
  (`mkdir`, `ListenUnix`, umask), `ipc.UAPIListen` auf Linux ein
  `net.FileListener` plus eine inotify-Überwachung auf den Socket-Pfad, die ein
  Embedder nicht braucht. Beides lässt sich in `api-android.go` mit eigenem
  Verzeichnis nachbauen; `dev.IpcHandle(conn)` ist öffentlich.

Damit wird wireguard-go eine normale `go.mod`-Abhängigkeit mit Versionsnummer
statt eines `replace` auf ein gepatchtes Submodul. Das Submodul entfällt, die
Host-Tests bauen automatisch denselben Stand wie die App (behebt die Kritik in
Abschnitt 1), und `apply-patches.sh` hat nur noch eine Serie.

### Empfehlung: **behalten** (Serie statt Fork), mit Triage

- Sofort: 0001 bereinigen; Serie für DCO umschreiben; 0002, 0010, 0011
  (Router-Liste), 0004 (fd=-1), 0005 (Trunkierung) als Einzelpatches einreichen.
- Danach: 0003, 0004, 0005, 0007, 0009, 0012, zuletzt 0006.
- wireguard-go: beide Patches **ersetzen** durch Go-Code in `native/libwg-go`,
  Submodul entfernen.
- Lokal bleiben: 0008 und was aus Abschnitt 2 dazukommt.

Ein Fork wird erst dann richtig, wenn Upstream 0006 ablehnt **und** der
In-Process-DHT viel Code in `udht.c` verändert. Dann ist ein Fork mit
Rebase-Branch ehrlicher als eine 20-teilige Serie. Heute ist er es nicht.

## 5. Die JNI-Fassade in `native/core`

### Ist-Zustand

`unetd_core.c` ersetzt `main.c`: ein pthread mit uloop, ein eventfd als
Kommandokanal, ein Kommando zu einer Zeit unter `api_lock`, Antworten über
condvar. Vier Kommandos (add, remove, status, stop), zwei Upcalls
(`protect_socket`, `network_update`), Status als JSON-String. `unetd_jni.c` ist
tatsächlich dünn. `unetd_log.c` ersetzt prozessweit fd 1 und 2 durch eine Pipe
und hält einen Ring von 1024 Zeilen à 2 KB.

Das Grundmuster ist richtig: ein Thread, der uloop gehört; nichts anderes
berührt unetd-Zustand; Upcalls posten nur. M1a beweist Start/Stop zweimal im
Prozess.

### Was ich kritisiere

- **Synchrone Kommandos ohne Timeout auf einen Loop, der blockiert.**
  `run_cmd` wartet unbegrenzt (`unetd_core.c:251-252`). Der Loop hängt in
  `getaddrinfo()` für Gateways und STUN-Server, auf einem Telefon ohne Netz
  minutenlang (F7). `Unetd.status()` wird jede Sekunde vom Poller gerufen und
  blockiert dann den IO-Dispatcher; `Unetd.stop()` blockiert `stopTunnel`, der
  Benachrichtigungstext bleibt stehen, der Service wirkt eingefroren. F7 ist ein
  Workaround für genau dieses Design.
- **Status ist Pull, Ereignisse gibt es nicht.** unetd weiß, wann ein Peer
  verbunden ist, wann die Netzdaten eine neue Version haben, wann STUN
  geantwortet hat. Die Fassade bietet nur `status()`, also baut die App drei
  Polling-Schleifen darüber (Abschnitt 6).
- **`protect_socket` ist `void`.** Schlägt `protect()` fehl, läuft unetd weiter
  und sendet in den eigenen Tunnel oder, mit Lockdown, ins Nichts. Das Log sagt
  `REFUSED`, der Code reagiert nicht.
- **`current_env()` gibt `uloop_env` an jeden Thread zurück**, der fragt
  (`unetd_jni.c:29-45`). Heute kommen alle Upcalls vom uloop-Thread; die erste
  Änderung, die das bricht, nutzt ein fremdes `JNIEnv`.
- **Logging über fd-2-Capture** ist ein prozessweiter Eingriff (auch Go schreibt
  dort hin, was gewollt ist), `NewStringUTF` auf Rohbytes aus dem Netz ist
  undefiniert für Nicht-Modified-UTF-8, und `logTail()` kopiert bis zu 400 Zeilen
  pro Sekunde für den Log-Screen. `AppLog.line` muss über JNI nach stderr
  schreiben, weil `System.err` nicht fd 2 ist.
- Globale Singletons (`core`, `callbacks`, `protector`) sind in Ordnung für eine
  Instanz, aber `UnetVpnService` hat keine Instanz-Zählung: `stopTunnel` läuft
  nach `onDestroy` asynchron, und ein neuer Service-Start kann ein `Unetd.stop()`
  des alten treffen. Jeder `stopTunnel` endet in `stopSelf` → `onDestroy` →
  zweiter `stopTunnel`, der die Fehlermeldung mit `null` überschreibt
  (`UnetVpnService.kt:354-383`). Dazu passt: `nativeStart` tauscht die
  Callback-Referenz aus, **bevor** `unetd_core_start` mit `-EALREADY` ablehnen
  kann (`unetd_jni.c:135-152`); der laufende uloop-Thread kann die gerade
  gelöschte Referenz benutzen.
- **`update_cmd` forkt trotz Hook.** Patch 0006 ruft `network_update` *vor* dem
  `fork+exec` von `update_cmd` auf, nimmt es aber nicht weg (upstream
  `network.c`, Hunk in Patch 0006). Über das Feld „Raw JSON“ in Setup kann ein
  Nutzer `update_cmd` setzen und damit `fork()` plus `waitpid()` auf dem
  uloop-Thread eines ART-Prozesses auslösen. Der Hook sollte `update_cmd`
  ersetzen, nicht ergänzen, oder `core` streicht das Feld beim `network_add`.
- **Ein fehlgeschlagener globaler PEX-Socket wird nicht gemeldet.**
  `core.pex_ok` wird berechnet (`unetd_core.c:291`) und nie zurückgegeben;
  `start` liefert 0, die App läuft „verbunden“ ohne Peer-Exchange, und nur
  `"pex_socket": false` im Status verrät es. Port 51819 belegt (ein zweiter
  unetd-Client, ein anderes Programm) ist genau dieser Fall.
- Nach `stop()` zeigen unetds Globals `data_dir` und `wg_user_socket_dir` auf
  Speicher, den `free_config()` freigegeben hat (`unetd_core.c:282-283, 325-331`).
  Heute harmlos, weil nach dem Stop kein unetd-Code mehr läuft; beim nächsten
  Start werden sie neu gesetzt. Beim ersten Refactoring, das den Loop länger
  leben lässt, ist das ein Use-after-free.
- Der Netzwerkname ist unvalidierte Nutzereingabe und wird Pfadbestandteil
  (`<socketDir>/<name>.sock`, `<name>.bin`).
- `do_status` liest unetd-interne Strukturfelder (`net->stun.port_ext`,
  `net->pex.fd.fd`, `peer->indirect`) direkt (`unetd_core.c:168-184`). Das
  koppelt die Fassade an das private Layout; jeder Upstream-Bump kann das
  stillschweigend brechen. Eine Status-Erweiterung gehört zu Patch 0007.

### Empfehlung: **behalten**, an vier Stellen ändern

1. `status()` wird nicht mehr ausgeführt, sondern gelesen: der uloop-Thread
   schreibt den Status-JSON in einen mutex-geschützten Puffer, periodisch
   (1 s, deckt die Zähler) und bei Ereignissen; der Aufrufer bekommt die letzte
   Kopie sofort. `stop()` bekommt einen Timeout mit `pthread_cancel`-freiem
   Fallback (den tun zuerst schließen bleibt, F7).
2. Ein dritter Upcall `on_event(kind)` für Peer-up/down, Netzdaten-Version,
   STUN-Ergebnis, DHT-Zustand, abgeleitet aus `network_pex_event` und dem
   Connect-Timer in `host.c`, hinter dem Platform-Hook (Patch 0006 wächst).
3. `protect_socket` gibt `bool` zurück; `pex_open` und `network_stun_open_socket`
   brechen bei `false` ab, statt unbemerkt weiterzulaufen.
4. Log als Callback statt fd-Capture: unetd hat `unetd_debug_printf`, das
   `core` bereits besitzt; wireguard-gos Logger wird ein cgo-Callback. Beide
   speisen denselben Ring, der dann nur noch Deltas liefert (`logSince(seq)`).
5. Zwei kleine Korrektheitsfixes unabhängig vom Rest: `start()` meldet einen
   fehlgeschlagenen globalen PEX-Socket als Fehler; `update_cmd` wird bei
   gesetztem Platform-Hook ignoriert (Änderung an Patch 0006).

Der Kommandokanal, der Thread, JSON als Statusformat und die Schlüsselfunktionen
bleiben.

## 6. Compose-UI mit StateFlow-Polling oder ereignisgetrieben

### Ist-Zustand

Kein ViewModel, keine Navigation-Bibliothek, drei Screens. `TunnelRuntime` ist
ein prozessweites Objekt mit `StateFlow<NetworkStatus>`, `uiVisible` und einem
`refresh`-SharedFlow; nur der Service schreibt. Die Activity sammelt mit
`collectAsStateWithLifecycle`. Das ist für diese App-Größe angemessen und
bleibt.

Was tatsächlich pollt:

| Schleife | Takt | Wo | Thread |
|---|---|---|---|
| unetd-Status | 1 s sichtbar oder „nicht settled“, sonst 30 s | `UnetVpnService.kt:264-287` | IO |
| DHT-Logdatei | 2 s | `AppRoot.kt:34-42` | Main |
| Log-Screen | 1 s, bis 400 Zeilen kopieren + Datei lesen + konkatenieren | `LogScreen.kt:43-54`, `AppRoot.kt:70-74` | Main |
| DHT-Pings | 15 s | `UnetVpnService.kt:298-336` | control |
| unetd intern | 1 s UAPI `get` pro Connect-Tick, 500 ms PEX-Timer | upstream `host.c`, `pex.c` | uloop |

### Was ich kritisiere

- „Polling folgt dem Bildschirm“ (`README.md:385-387`) stimmt nicht: ein Tunnel,
  dessen Peers offline bleiben, pollt **im Hintergrund dauerhaft mit 1 Hz**
  (`Zeile 281-282`, „settled“ verlangt einen verbundenen Peer). Das ist der
  Batteriefall, den F16 vermeiden wollte.
- Zwei der drei UI-Schleifen existieren nur, weil der DHT ein anderer Prozess
  ist und der Log ein Ring ohne Delta-API. Mit Abschnitt 2 und 5 entfallen sie.
- `uiVisible` ist ein Bool, keine Zählung; zwei Activity-Instanzen (die
  Notification startet `MainActivity` ohne `launchMode`) können es gegenseitig
  löschen.
- `refresh` hat `extraBufferCapacity = 1` ohne Replay; ein `tryEmit`, während der
  Poller in `Unetd.status()` steckt, geht verloren.
- `TunnelRuntime.update` ist nicht atomar und wird von zwei Threads benutzt.
- Der Log-Screen ist ein einziger `Text` mit erzwungenem Scroll ans Ende; man
  kann nicht oben bleiben, während Zeilen ankommen.
- `SetupScreen` leitet bei jedem Tastendruck den Pubkey über JNI auf dem
  Main-Thread ab (`SetupScreen.kt:72`); der private Schlüssel liegt in
  `rememberSaveable` und damit im Saved-State-Bundle.
- Privater Schlüssel im Klartext in SharedPreferences: dasselbe macht die
  offizielle App mit ihren Konfigurationsdateien, und der Android-Keystore kann
  einen Curve25519-Schlüssel nicht für wireguard-go nutzbar halten. Kein
  Designfehler, aber aus dem Saved-State gehört er raus.

### Ereignisgetrieben, aber nicht dogmatisch

Zähler (rx/tx, Handshake-Alter) sind inhärent Polling; wireguard-go liefert sie
nur auf `get`. Ereignisse (Peer up/down, Netzdaten, STUN, DHT-Fortschritt,
Fehler) kommen aus unetd und sollten als solche ankommen. Die richtige Form ist
deshalb: **Ereignisse aus der Fassade (Abschnitt 5, Punkt 2) treiben den
`StateFlow`; Polling nur für Zähler und nur bei sichtbarem UI.** Die
Benachrichtigung und die DHT-Policy hängen dann an Ereignissen und brauchen
den 30-s-Hintergrund-Poll nicht mehr.

### Empfehlung: **behalten** (StateFlow, kein ViewModel-Zwang), Quellen **ändern**

- Hintergrund-Poll streichen, Ereignisse aus der Fassade einspeisen; „settled“
  darf keine Polling-Bedingung mehr sein.
- Log als `SharedFlow<String>` mit Sequenznummer; `LazyColumn` statt einem Text;
  Auto-Scroll nur, wenn der Nutzer unten ist.
- `uiVisible` als Zählung oder über `ProcessLifecycleOwner`.
- `DhtLog` und die 2-s-Datei-Schleife entfallen mit Abschnitt 2.
- Ein kleines ViewModel lohnt erst, wenn es mehr als einen Screen mit eigenem
  Zustand gibt; `AppRoot` ist heute das ViewModel. In Ordnung.

## 7. Fundstücke außerhalb der sechs Fragen

Beim Lesen aufgefallen, ohne Anspruch auf Vollständigkeit; nichts davon ist
Teil der Empfehlungen oben, aber jedes gehört auf eine Liste.

- `NetDiag.run` läuft nach jedem Verbinden und jedem Re-Establish, unabhängig
  vom Debug-Schalter, mit HTTPS zu Google, TCP zu einer festen IP, DNS zu 8.8.8.8,
  Pings an fünf öffentliche DHT-Router und zwei STUN-Server
  (`NetDiag.kt`, `UnetVpnService.kt:135, 252`). Das ist Diagnose, die jeder
  Nutzer immer bezahlt, auch mit privatem Bootstrap.
- `onStartCommand` mit unvollständiger Config ruft `stopSelf()` ohne
  `startForeground`; nach `startForegroundService` ist das auf API 26+ eine
  `ForegroundServiceDidNotStartInTimeException`-Falle (`UnetVpnService.kt:96-100`).
- Der Service-`CoroutineScope` wird nie gecancelt; `NetDiag`-Coroutinen können
  den Service überleben (`UnetVpnService.kt:67`).
- Re-Establish schließt den alten tun, bevor der neue existiert; Android hat
  für diesen Moment keine VPN (`applyTunSettings`). Die offizielle App etabliert
  den neuen zuerst.
- Kein `ConnectivityManager.NetworkCallback`, kein `setUnderlyingNetworks`.
  Roaming auf dem Telefon ist laut README unverifiziert; mit F15 hängt es
  vollständig an unetds Kandidaten-Rotation.
- `versionCode` ist die Commit-Zahl des **aktuellen Branches**; auf einem
  Feature-Branch kann sie kleiner sein als auf `main`.
- `ipc.UAPIOpen` setzt `umask(077)` für den ganzen Prozess, solange es läuft
  (upstream `ipc/uapi_unix.go`). Kurz, aber prozessweit, und ein Grund mehr,
  die UAPI-Öffnung selbst zu schreiben (Abschnitt 4).
- `wgVersion` liefert bei einem Verzeichnis-`replace` vermutlich einen leeren
  String, weil `dep.Replace.Version` dann leer ist (`api-android.go:244-246`);
  mit einer normalen Modulabhängigkeit stimmt die Zeile im Log wieder.
- `udht_jni.c` ruft `uloop_init()` ohne die Signal-Rettung aus `unetd_core.c`;
  der `:dht`-Prozess bekommt uloops SIGINT/SIGTERM/SIGCHLD-Handler. Entfällt
  mit Abschnitt 2.
- Zwei Threads (C via `fprintf`, Go via `os.Stderr`) schreiben ungepuffert auf
  denselben fd 2; Zeilen können sich verschränken. Entfällt mit dem Log-Callback
  aus Abschnitt 5.
- Doku-Drift: README zählt 8 Patches (es sind 12), beschreibt den UAPI-Pfad als
  Compile-Zeit-Konstante (er ist Laufzeit), nennt drei Bootstrap-Router (NetDiag
  prüft fünf), und `FakeUnetRepository` treibt keine Preview mehr.

## 8. Vorgeschlagene Reihenfolge, falls freigegeben

1. Doku und Tests ehrlich machen (Abschnitt 3 „Jetzt“, Abschnitt 7 Doku-Drift,
   NAT-Testbed ohne `CAP_NET_RAW`). Kein Produktcode.
2. Patch-Serie bereinigen und erste Einzelpatches einreichen (Abschnitt 4).
3. wireguard-go-Patches durch Go-Code ersetzen, Submodul entfernen (Abschnitt 4).
   Danach laufen Host-Tests gegen dasselbe wireguard-go.
4. Fassade: nicht-blockierender Status, Ereignis-Upcall, `protect` mit Rückgabe
   (Abschnitt 5). UI auf Ereignisse umstellen (Abschnitt 6).
5. DHT in den Prozess holen (Abschnitt 2), M1b erweitern.
6. Nur mit Feldbefund: STUN-Multiplexer (Abschnitt 3, Stufe 2).

Jeder Schritt lässt CI grün und die Register-Einträge F1 bis F18 unangetastet.

## 9. Fragen an dich

- Abschnitt 2: In-Process-DHT ist der eingreifendste Vorschlag. Reicht dir die
  Begründung, oder willst du erst den Teardown-Prototyp in `udht.c` sehen?
- Abschnitt 4: Soll die Serie unter deinem Namen mit Sign-off eingereicht
  werden? Ohne das bleibt sie lokal, egal wie gut sie ist.
- Abschnitt 3: Gibt es einen Befund von einem nicht port-erhaltenden NAT auf
  Telefonseite? Davon hängt ab, ob Stufe 2 überhaupt auf die Liste gehört.
