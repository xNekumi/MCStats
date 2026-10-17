# MCStats

Live-Statistiken für einen **Minecraft 26.3 Fabric-Server**: wer ist online, wer spielt am meisten,
wer stirbt am häufigsten, wer baut die meisten Diamanten ab – plus ein Live-Feed mit Toden und
Fortschritten und eine eigene Seite für jeden Spieler.

Das Projekt besteht aus zwei unabhängigen Teilen:

```
┌──────────────────────────┐        ┌───────────────────────────┐        ┌───────────┐
│ Minecraft-Server (Fabric)│  JSON  │ Website (Node.js)         │  HTTP  │ Besucher  │
│  └ MCStats-Mod           │ ─────► │  · merkt sich letzten     │ ─────► │ (Browser) │
│    Daten-API :8765       │  live  │    Stand (data/state.json)│  live  │           │
│    (nur intern, Token)   │        │  · liefert Seite + API    │        │           │
└──────────────────────────┘        └───────────────────────────┘        └───────────┘
```

- **`mod/`** – der Fabric-Mod. Sammelt die Daten und stellt sie über eine kleine JSON-API bereit.
  Er liefert *keine* Website aus.
- **`website/`** – die eigenständige Website. Ein kleiner Node.js-Server ohne Abhängigkeiten holt
  die Daten live vom Mod, speichert den letzten Stand und liefert die Seite aus. Ist der
  Minecraft-Server offline, läuft die Website weiter und zeigt „Server offline · letzter Stand …“.

## Was gesammelt wird

| Kennzahl          | Quelle                                                         |
|-------------------|----------------------------------------------------------------|
| Spielzeit         | `minecraft:play_time`                                          |
| Tode              | `minecraft:deaths`                                             |
| Diamanterze       | abgebaute `diamond_ore` + `deepslate_diamond_ore`              |
| Blöcke abgebaut   | Summe aller `minecraft:mined`                                  |
| Mobs erlegt       | `minecraft:mob_kills`                                          |
| Spieler besiegt   | `minecraft:player_kills`                                       |
| Strecke           | Summe aller `*_one_cm`-Werte außer Fallen                      |
| Totems            | benutzte `totem_of_undying`                                    |
| Schaden           | `damage_dealt` / `damage_taken`                                |
| Mahlzeiten        | benutzte Items mit Nahrungswert + Kuchenstücke                 |
| Fortschritte      | erledigte Fortschritte mit Anzeige (ohne Rezepte)              |
| … und mehr        | Sprünge, Fische, Handel, Zucht, Hergestelltes                  |

Dazu kommen ein **Feed** (Tode inkl. Todesnachricht und Killer, Fortschritte) und pro Spieler
die meistabgebauten Blöcke, meisterlegten Mobs, Todesursachen und alle Vanilla-Statistiken.

Die Daten kommen aus den normalen Minecraft-Statistiken. Spieler, die vor der Installation des Mods
schon gespielt haben, sind also sofort mit allen Werten dabei. Nur der Feed beginnt ab der Installation.

Todesnachrichten, Fortschrittsnamen, Block- und Mobnamen sind **auf Deutsch**: Der Mod lädt die
Sprachdatei beim ersten Start einmalig von Mojangs Asset-Server (wie der Launcher) und legt sie unter
`config/mcstats/lang/` ab.

## Installation

### 1. Mod auf dem Minecraft-Server

Voraussetzungen: Minecraft **26.3**, Fabric Loader **≥ 0.19.5**, [Fabric API](https://modrinth.com/mod/fabric-api), Java **25**.

1. `mcstats-1.0.0.jar` (siehe [Bauen](#bauen)) zusammen mit der Fabric API in den `mods/`-Ordner legen.
2. Server einmal starten. Dabei entsteht `config/mcstats/config.json`.
3. In der Konfiguration ein **`apiToken`** setzen (ein langes, zufälliges Passwort, z.B. `openssl rand -hex 32`).
4. Server neu starten. Im Log steht dann `Daten-API läuft auf http://127.0.0.1:8765/api/`.

`config/mcstats/config.json`:

| Feld             | Standard      | Bedeutung |
|------------------|---------------|-----------|
| `bindAddress`    | `127.0.0.1`   | Nur lokal erreichbar. Läuft die Website auf einem anderen Rechner, `0.0.0.0` setzen und den Port per Firewall nur für diesen Rechner freigeben. |
| `port`           | `8765`        | Port der Daten-API |
| `apiToken`       | leer          | Wenn gesetzt, muss jede Anfrage `Authorization: Bearer <token>` mitschicken. |
| `corsOrigin`     | leer          | Nur nötig, wenn Browser die Mod-API direkt abfragen sollen (normalerweise nicht). |
| `refreshSeconds` | `30`          | Wie oft die Stats der Online-Spieler neu eingelesen werden. Join/Leave wird sofort erkannt. |
| `feedSize`       | `100`         | Wie viele Feed-Einträge gespeichert werden (`world/mcstats/feed.json`). |
| `language`       | `de_de`       | Sprache für Todesnachrichten & Co. `en_us` = kein Download. |
| `serverName`     | `Mein Server` | Name, der auf der Website erscheint. |
| `hiddenPlayers`  | `[]`          | Namen oder UUIDs, die nirgends auftauchen sollen (Admins, Bots). |
| `displayNames`   | `{}`          | Anzeigenamen: `{"Minecraftname": "Name auf der Website"}` |
| `twitch`         | aus           | Optional: Live-Anzeige für Streamer, siehe unten. |

### 2. Website

Voraussetzung: **Node.js ≥ 18**. Es gibt keine Abhängigkeiten, `npm install` ist nicht nötig.

```bash
cd website
cp config.example.json config.json   # modUrl und modToken eintragen
node server.js                        # oder: npm start
```

`website/config.json`:

| Feld       | Standard                | Bedeutung |
|------------|-------------------------|-----------|
| `port`     | `3000`                  | Port der Website |
| `host`     | `0.0.0.0`               | Adresse, an die die Website gebunden wird |
| `modUrl`   | `http://127.0.0.1:8765` | Adresse der Mod-API |
| `modToken` | leer                    | Derselbe Wert wie `apiToken` im Mod |
| `dataDir`  | `data`                  | Hier liegt der gespeicherte letzte Stand |

Alle Werte lassen sich auch per Umgebungsvariable setzen: `PORT`, `HOST`, `MOD_URL`, `MOD_TOKEN`, `DATA_DIR`.

Aussehen und Bestenlisten anpassen: `website/public/config.js` (Titel, welche Bestenlisten in welcher
Reihenfolge, Quelle der Skin-Köpfe). Texte und Design liegen in `website/public/`.

#### Als Dienst (systemd)

```ini
# /etc/systemd/system/mcstats-web.service
[Unit]
Description=MCStats Website
After=network.target

[Service]
WorkingDirectory=/opt/mcstats/website
ExecStart=/usr/bin/node server.js
Restart=always
User=mcstats

[Install]
WantedBy=multi-user.target
```

#### Hinter nginx (HTTPS)

```nginx
location / {
    proxy_pass http://127.0.0.1:3000;
    proxy_set_header Host $host;
}
location /api/live {
    proxy_pass http://127.0.0.1:3000;
    proxy_buffering off;          # Live-Updates nicht puffern
    proxy_read_timeout 1h;
}
```

### Twitch (optional)

Für die „LIVE“-Anzeige eine App unter <https://dev.twitch.tv/console/apps> anlegen und im Mod eintragen:

```json
"twitch": {
  "enabled": true,
  "clientId": "…",
  "clientSecret": "…",
  "pollSeconds": 60,
  "channels": { "Minecraftname": "twitchlogin" }
}
```

## API

Die Website stellt dieselbe API wie der Mod bereit, ergänzt um `serverOnline` und `lastContact`.

| Endpunkt                | Inhalt |
|-------------------------|--------|
| `GET /api/stats`        | Überblick, Summen, Online-Liste, alle Bestenlisten (Top 25) |
| `GET /api/feed`         | letzte Tode und Fortschritte |
| `GET /api/players`      | alle Spieler mit Spielzeit und „zuletzt online“ |
| `GET /api/player/{id}`  | Details eines Spielers (UUID oder Name) |
| `GET /api/live`         | Server-Sent Events: `hello`, `stats`, `event` (und auf der Website `status`) |

## Bauen

```bash
cd mod
./gradlew build          # braucht JDK 25
# Ergebnis: mod/build/libs/mcstats-1.0.0.jar
```

Zum Testen startet `./gradlew runServer` einen Entwicklungsserver mit dem Mod.

## Hinweise

- Kein offizielles Minecraft-Produkt, nicht von Mojang oder Microsoft genehmigt oder mit ihnen verbunden.
- Die Spielerköpfe kommen standardmäßig von [mc-heads.net](https://mc-heads.net) (in `website/public/config.js` änderbar).
