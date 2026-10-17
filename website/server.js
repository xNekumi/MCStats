#!/usr/bin/env node
/*
 * MCStats-Website – eigenständiger Server (Node.js >= 18, keine Abhängigkeiten).
 *
 * Holt die Daten live von der Daten-API des Minecraft-Mods, merkt sich den letzten Stand
 * (auch über Neustarts hinweg) und liefert die Website samt eigener API an die Besucher aus.
 * Ist der Minecraft-Server offline, läuft die Website weiter und zeigt den letzten Stand.
 *
 * Konfiguration über config.json (siehe config.example.json) oder Umgebungsvariablen:
 *   PORT, HOST, MOD_URL, MOD_TOKEN, DATA_DIR
 */
"use strict";

const http = require("node:http");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");

// ------------------------------------------------------------------ Konfiguration

const fileConfig = (() => {
	const file = path.join(__dirname, "config.json");
	if (!fs.existsSync(file)) return {};
	try {
		return JSON.parse(fs.readFileSync(file, "utf8"));
	} catch (err) {
		console.error(`[mcstats-web] config.json ist kein gültiges JSON: ${err.message}`);
		process.exit(1);
	}
})();

const config = {
	port: Number(process.env.PORT || fileConfig.port || 3000),
	host: process.env.HOST || fileConfig.host || "0.0.0.0",
	modUrl: (process.env.MOD_URL || fileConfig.modUrl || "http://127.0.0.1:8765").replace(/\/+$/, ""),
	modToken: process.env.MOD_TOKEN ?? fileConfig.modToken ?? "",
	dataDir: path.resolve(__dirname, process.env.DATA_DIR || fileConfig.dataDir || "data"),
	publicDir: path.join(__dirname, "public"),
	maxLiveClients: Number(fileConfig.maxLiveClients || 1000),
};

const log = (...args) => console.log(new Date().toISOString(), "[mcstats-web]", ...args);

// ------------------------------------------------------------------ Zustand

const STATE_FILE = path.join(config.dataDir, "state.json");
const FEED_SIZE = 50;
const PLAYER_CACHE_SIZE = 1000;
const PLAYER_CACHE_TTL = 15_000;

const state = {
	stats: null,         // letzter Snapshot vom Mod
	feed: [],            // neueste zuerst
	players: [],         // Spielerliste
	connected: false,    // besteht gerade eine Verbindung zum Mod?
	lastContact: null,   // ISO-Zeit des letzten Lebenszeichens vom Mod
};
const playerCache = new Map(); // uuid -> { data, fetchedAt }
const playerIds = new Map();   // name (klein) -> uuid

function loadState() {
	try {
		const saved = JSON.parse(fs.readFileSync(STATE_FILE, "utf8"));
		state.stats = saved.stats ?? null;
		state.feed = Array.isArray(saved.feed) ? saved.feed : [];
		state.players = Array.isArray(saved.players) ? saved.players : [];
		state.lastContact = saved.lastContact ?? null;
		for (const p of saved.playerDetails ?? []) cachePlayer(p, 0);
		log(`Letzten Stand geladen (${state.players.length} Spieler, ${state.feed.length} Ereignisse)`);
	} catch (err) {
		if (err.code !== "ENOENT") log("state.json konnte nicht gelesen werden:", err.message);
	}
}

let saveTimer = null;
function scheduleSave() {
	if (saveTimer) return;
	saveTimer = setTimeout(() => {
		saveTimer = null;
		saveState().catch((err) => log("Speichern fehlgeschlagen:", err.message));
	}, 5000);
}

async function saveState() {
	const data = {
		stats: state.stats,
		feed: state.feed,
		players: state.players,
		lastContact: state.lastContact,
		playerDetails: [...playerCache.values()].map((e) => e.data),
	};
	await fsp.mkdir(config.dataDir, { recursive: true });
	const tmp = STATE_FILE + ".tmp";
	await fsp.writeFile(tmp, JSON.stringify(data));
	await fsp.rename(tmp, STATE_FILE);
}

function cachePlayer(data, fetchedAt = Date.now()) {
	if (!data || !data.uuid) return;
	playerCache.delete(data.uuid); // neu einsortieren = zuletzt benutzt
	playerCache.set(data.uuid, { data, fetchedAt });
	playerIds.set(String(data.name).toLowerCase(), data.uuid);
	if (data.minecraft) playerIds.set(String(data.minecraft).toLowerCase(), data.uuid);
	while (playerCache.size > PLAYER_CACHE_SIZE) playerCache.delete(playerCache.keys().next().value);
}

function cachedPlayer(id) {
	const key = String(id).toLowerCase();
	return playerCache.get(key) || playerCache.get(playerIds.get(key));
}

const eventKey = (e) => `${e.type}|${e.at}|${e.player && e.player.uuid}`;

function addEvent(event) {
	const key = eventKey(event);
	if (state.feed.some((e) => eventKey(e) === key)) return false;
	state.feed = [event, ...state.feed].slice(0, FEED_SIZE);
	return true;
}

/** Was die Besucher sehen: bei Verbindungsverlust gilt niemand mehr als online. */
function publicStats() {
	if (!state.stats) return null;
	const base = { ...state.stats, serverOnline: state.connected, lastContact: state.lastContact };
	if (!state.connected) Object.assign(base, { online: [], onlineCount: 0, live: [] });
	return base;
}

/** Wer beim letzten Kontakt online war, war es bis zum Verbindungsverlust. */
function wasOnline(uuid) {
	return Boolean(state.stats && state.stats.online && state.stats.online.some((p) => p.uuid === uuid));
}

function offlineView(player) {
	const lastSeen = wasOnline(player.uuid) && state.lastContact ? state.lastContact : player.lastSeen;
	return { ...player, online: false, lastSeen };
}

function publicPlayer(data) {
	if (state.connected) return { ...data, serverOnline: true };
	return { ...offlineView(data), live: undefined, serverOnline: false, lastContact: state.lastContact };
}

// ------------------------------------------------------------------ Verbindung zum Mod

const modHeaders = () => (config.modToken ? { Authorization: `Bearer ${config.modToken}` } : {});

async function modJson(pathname, timeoutMs = 8000) {
	const res = await fetch(config.modUrl + "/api" + pathname, { headers: { ...modHeaders(), accept: "application/json" }, signal: AbortSignal.timeout(timeoutMs) });
	if (res.status === 404) return null;
	if (!res.ok) throw new Error(`HTTP ${res.status} für ${pathname}`);
	return res.json();
}

function setConnected(value) {
	if (value) state.lastContact = new Date().toISOString();
	if (state.connected === value) return;
	state.connected = value;
	log(value ? `Mit dem Mod verbunden (${config.modUrl})` : "Verbindung zum Mod verloren – zeige letzten Stand");
	broadcast("status", { serverOnline: value, lastContact: state.lastContact });
	if (state.stats) broadcast("stats", publicStats());
}

async function refreshPlayers() {
	try {
		const data = await modJson("/players");
		if (data) {
			state.players = data.players;
			scheduleSave();
		}
	} catch {
		/* nächster Versuch beim nächsten Intervall */
	}
}

/**
 * Hält eine Server-Sent-Events-Verbindung zum Mod offen und baut sie bei Fehlern neu auf.
 * Der Mod schickt alle 20 s ein Ping; bleibt 60 s alles still, wird neu verbunden.
 */
async function liveLoop() {
	let delay = 2000;
	let warnedAuth = false;
	for (;;) {
		const controller = new AbortController();
		let watchdog = setTimeout(() => controller.abort(), 60_000);
		const alive = () => {
			clearTimeout(watchdog);
			watchdog = setTimeout(() => controller.abort(), 60_000);
			setConnected(true);
		};
		try {
			const res = await fetch(config.modUrl + "/api/live", { headers: { ...modHeaders(), accept: "text/event-stream" }, signal: controller.signal });
			if (res.status === 401) {
				if (!warnedAuth) log("Der Mod lehnt den Token ab – stimmen modToken und apiToken überein?");
				warnedAuth = true;
				throw new Error("401");
			}
			if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
			warnedAuth = false;
			delay = 2000;
			const decoder = new TextDecoder();
			let buffer = "";
			for await (const chunk of res.body) {
				buffer += decoder.decode(chunk, { stream: true }).replace(/\r\n/g, "\n");
				let end;
				while ((end = buffer.indexOf("\n\n")) >= 0) {
					const block = buffer.slice(0, end);
					buffer = buffer.slice(end + 2);
					alive();
					handleSse(block);
				}
			}
			throw new Error("Stream beendet");
		} catch (err) {
			if (state.connected) log("Live-Verbindung getrennt:", err.message);
			setConnected(false);
		} finally {
			clearTimeout(watchdog);
		}
		await new Promise((r) => setTimeout(r, delay));
		delay = Math.min(delay * 2, 30_000);
	}
}

/** Nach /mcstats reset (oder undo) sind gespeicherte Spielerdetails veraltet. */
function setStats(stats) {
	const before = state.stats && state.stats.countingSince;
	state.stats = stats;
	if (stats && (stats.countingSince ?? null) !== (before ?? null) && before !== undefined) {
		playerCache.clear();
		playerIds.clear();
		refreshPlayers();
	}
}

function handleSse(block) {
	let event = "message";
	const data = [];
	for (const line of block.split("\n")) {
		if (line.startsWith("event:")) event = line.slice(6).trim();
		else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
	}
	if (data.length === 0) return; // Ping oder retry-Zeile
	let payload;
	try {
		payload = JSON.parse(data.join("\n"));
	} catch {
		return;
	}
	if (event === "hello") {
		setStats(payload.stats);
		state.feed = (payload.feed || []).slice(0, FEED_SIZE); // der Mod ist die Quelle, z.B. nach einem Reset
		refreshPlayers();
		broadcast("stats", publicStats());
		broadcast("feed", state.feed);
	} else if (event === "stats") {
		setStats(payload);
		broadcast("stats", publicStats());
	} else if (event === "event") {
		if (addEvent(payload)) broadcast("event", payload);
	} else if (event === "feed") {
		state.feed = Array.isArray(payload) ? payload.slice(0, FEED_SIZE) : [];
		broadcast("feed", state.feed);
	}
	scheduleSave();
}

// ------------------------------------------------------------------ Live-Verbindungen der Besucher

const clients = new Set();

function broadcast(event, data) {
	const message = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
	for (const res of clients) res.write(message);
}

setInterval(() => {
	for (const res of clients) res.write(": ping\n\n");
}, 25_000).unref();

function live(req, res) {
	if (clients.size >= config.maxLiveClients) return sendJson(res, 503, { error: "Zu viele Verbindungen" });
	res.writeHead(200, {
		"Content-Type": "text/event-stream; charset=utf-8",
		"Cache-Control": "no-store",
		Connection: "keep-alive",
		"X-Accel-Buffering": "no",
	});
	res.write(`retry: 5000\n\nevent: hello\ndata: ${JSON.stringify({ stats: publicStats(), feed: state.feed })}\n\n`);
	clients.add(res);
	req.on("close", () => clients.delete(res));
}

// ------------------------------------------------------------------ HTTP

const TYPES = {
	".html": "text/html; charset=utf-8",
	".css": "text/css; charset=utf-8",
	".js": "text/javascript; charset=utf-8",
	".json": "application/json; charset=utf-8",
	".svg": "image/svg+xml",
	".png": "image/png",
	".webp": "image/webp",
	".ico": "image/x-icon",
	".woff2": "font/woff2",
	".txt": "text/plain; charset=utf-8",
};

function sendJson(res, status, body) {
	const text = JSON.stringify(body);
	res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "Content-Length": Buffer.byteLength(text) });
	res.end(text);
}

async function handleApi(req, res, pathname) {
	if (pathname === "/api/stats") {
		const stats = publicStats();
		return stats ? sendJson(res, 200, stats) : sendJson(res, 503, { error: "Noch keine Daten vom Server", serverOnline: false });
	}
	if (pathname === "/api/feed") return sendJson(res, 200, { feed: state.feed, serverOnline: state.connected });
	if (pathname === "/api/players") {
		const players = state.connected ? state.players : state.players.map(offlineView);
		return sendJson(res, 200, { players, serverOnline: state.connected, lastContact: state.lastContact });
	}
	if (pathname === "/api/live") return live(req, res);

	const match = pathname.match(/^\/api\/player\/([^/]{1,64})$/);
	if (match) {
		let id;
		try {
			id = decodeURIComponent(match[1]);
		} catch {
			return sendJson(res, 400, { error: "Ungültiger Name" });
		}
		const cached = cachedPlayer(id);
		if (cached && state.connected && Date.now() - cached.fetchedAt < PLAYER_CACHE_TTL) return sendJson(res, 200, publicPlayer(cached.data));
		if (state.connected) {
			try {
				const data = await modJson(`/player/${encodeURIComponent(id)}`, 5000);
				if (!data) return sendJson(res, 404, { error: "Spieler nicht gefunden" });
				cachePlayer(data);
				scheduleSave();
				return sendJson(res, 200, publicPlayer(data));
			} catch {
				/* auf den Cache zurückfallen */
			}
		}
		if (cached) return sendJson(res, 200, publicPlayer(cached.data));
		return sendJson(res, state.connected ? 502 : 503, { error: "Server gerade nicht erreichbar", serverOnline: false });
	}
	return sendJson(res, 404, { error: "Unbekannter Endpunkt" });
}

async function handleStatic(req, res, pathname) {
	let file = pathname;
	if (file.startsWith("/spieler/")) file = "/player.html"; // hübsche Spieler-URLs, der Name wird im Browser gelesen
	if (file.endsWith("/")) file += "index.html";
	const resolved = path.resolve(config.publicDir, "." + file);
	if (!resolved.startsWith(config.publicDir + path.sep)) return notFound(res);
	let target = resolved;
	try {
		let stat = await fsp.stat(target).catch(() => null);
		if (!stat && !path.extname(target)) {
			target += ".html";
			stat = await fsp.stat(target).catch(() => null);
		}
		if (!stat || !stat.isFile()) return notFound(res);
		const ext = path.extname(target);
		res.writeHead(200, {
			"Content-Type": TYPES[ext] || "application/octet-stream",
			"Content-Length": stat.size,
			"Cache-Control": ext === ".html" ? "no-cache" : "public, max-age=300",
			"X-Content-Type-Options": "nosniff",
		});
		if (req.method === "HEAD") return res.end();
		fs.createReadStream(target).pipe(res);
	} catch {
		notFound(res);
	}
}

function notFound(res) {
	res.writeHead(404, { "Content-Type": "text/html; charset=utf-8" });
	res.end('<!doctype html><meta charset="utf-8"><title>Nicht gefunden</title><p>Diese Seite gibt es nicht. <a href="/">Zur Startseite</a></p>');
}

const server = http.createServer(async (req, res) => {
	try {
		if (req.method !== "GET" && req.method !== "HEAD") {
			res.writeHead(405, { Allow: "GET, HEAD" });
			return res.end();
		}
		const { pathname } = new URL(req.url, "http://localhost");
		if (pathname.startsWith("/api/")) return await handleApi(req, res, pathname);
		let decoded;
		try {
			decoded = decodeURIComponent(pathname);
		} catch {
			return notFound(res);
		}
		return await handleStatic(req, res, decoded);
	} catch (err) {
		if (!res.headersSent) sendJson(res, 500, { error: "Interner Fehler" });
		else res.end();
		log("Fehler bei", req.url, err);
	}
});

// ------------------------------------------------------------------ Start

loadState();
server.listen(config.port, config.host, () => {
	log(`Website läuft auf http://${config.host === "0.0.0.0" ? "localhost" : config.host}:${config.port}/ – Daten von ${config.modUrl}`);
});
liveLoop();
setInterval(() => state.connected && refreshPlayers(), 60_000).unref();

for (const signal of ["SIGINT", "SIGTERM"]) {
	process.on(signal, async () => {
		log("Beende …");
		for (const res of clients) res.end();
		await saveState().catch(() => {});
		process.exit(0);
	});
}
