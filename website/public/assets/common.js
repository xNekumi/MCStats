/* MCStats – gemeinsame Helfer für alle Seiten. */
(() => {
	const cfg = Object.assign({ apiBase: "", title: "", headUrl: "", bodyUrl: "", boards: [] }, window.MCSTATS_CONFIG || {});
	const api = (path) => cfg.apiBase.replace(/\/$/, "") + "/api" + path;

	const nf = new Intl.NumberFormat("de-DE");
	const nf1 = new Intl.NumberFormat("de-DE", { maximumFractionDigits: 1 });
	const dateFmt = new Intl.DateTimeFormat("de-DE", { day: "numeric", month: "short", year: "numeric" });
	const timeFmt = new Intl.DateTimeFormat("de-DE", { weekday: "short", day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });

	const fmt = {
		num: (v) => nf.format(Math.round(v)),
		duration(seconds) {
			const d = Math.floor(seconds / 86400), h = Math.floor((seconds % 86400) / 3600), m = Math.floor((seconds % 3600) / 60);
			if (d > 0) return `${nf.format(d)} T ${h} Std`;
			if (h > 0) return `${h} Std ${m} Min`;
			return `${m} Min`;
		},
		hours: (seconds) => nf.format(Math.round(seconds / 3600)),
		distance: (m) => (m >= 1000 ? `${nf1.format(m / 1000)} km` : `${nf.format(m)} m`),
		hearts(tenths) {
			// Minecraft zählt Schaden in Zehntel halben Herzen
			const hearts = tenths / 20;
			return `${nf1.format(hearts)} ${hearts === 1 ? "Herz" : "Herzen"}`;
		},
		ago(iso, now = Date.now()) {
			const t = new Date(iso).getTime();
			if (Number.isNaN(t)) return "–";
			const s = Math.max(0, (now - t) / 1000);
			if (s < 60) return "gerade eben";
			if (s < 3600) return `vor ${Math.round(s / 60)} Min`;
			if (s < 86400) return `vor ${Math.round(s / 3600)} Std`;
			if (s < 86400 * 7) return `vor ${Math.round(s / 86400)} ${Math.round(s / 86400) === 1 ? "Tag" : "Tagen"}`;
			return `am ${dateFmt.format(t)}`;
		},
		when(iso, now = Date.now()) {
			const t = new Date(iso).getTime();
			return now - t < 12 * 3600 * 1000 ? fmt.ago(iso, now) : timeFmt.format(t);
		},
		date: (iso) => dateFmt.format(new Date(iso)),
	};

	/** Kennzahlen: Titel, Untertitel und Formatierung. */
	const metrics = {
		playtime:     { title: "Sitzfleisch",        sub: "Längste Spielzeit",          unit: fmt.duration, short: "Spielzeit" },
		diamonds:     { title: "Glitzerfinger",      sub: "Diamanterze abgebaut",       unit: fmt.num, short: "Diamanterze" },
		deaths:       { title: "Stammgast am Spawn", sub: "Die meisten Tode",           unit: fmt.num, short: "Tode" },
		blocksMined:  { title: "Maulwurf",           sub: "Blöcke abgebaut",            unit: fmt.num, short: "Blöcke abgebaut" },
		mobKills:     { title: "Nachtwache",         sub: "Mobs erlegt",                unit: fmt.num, short: "Mobs erlegt" },
		distance:     { title: "Fernweh",            sub: "Weiteste Strecke",           unit: fmt.distance, short: "Strecke" },
		totems:       { title: "Zweite Chance",      sub: "Totems ausgelöst",           unit: fmt.num, short: "Totems" },
		damageDealt:  { title: "Schlagkraft",        sub: "Schaden ausgeteilt",         unit: fmt.hearts, short: "Schaden ausgeteilt" },
		damageTaken:  { title: "Prellbock",          sub: "Schaden eingesteckt",        unit: fmt.hearts, short: "Schaden erlitten" },
		foodEaten:    { title: "Nimmersatt",         sub: "Mahlzeiten gegessen",        unit: fmt.num, short: "Mahlzeiten" },
		playerKills:  { title: "Kopfgeld",           sub: "Andere Spieler besiegt",     unit: fmt.num, short: "Spieler besiegt" },
		jumps:        { title: "Flummi",             sub: "Sprünge",                    unit: fmt.num, short: "Sprünge" },
		fishCaught:   { title: "Petri Heil",         sub: "Fische gefangen",            unit: fmt.num, short: "Fische" },
		trades:       { title: "Feilscher",          sub: "Mit Dorfbewohnern gehandelt", unit: fmt.num, short: "Handel" },
		animalsBred:  { title: "Bauernhof",          sub: "Tiere gezüchtet",            unit: fmt.num, short: "Tiere gezüchtet" },
		itemsCrafted: { title: "Werkbank-Profi",     sub: "Gegenstände hergestellt",    unit: fmt.num, short: "Hergestellt" },
		advancements: { title: "Fortschrittsjäger",  sub: "Fortschritte geschafft",     unit: fmt.num, short: "Fortschritte" },
	};

	/** Element bauen: el("a", {class: "x", href: "/"}, "Text", child) */
	function el(tag, attrs, ...children) {
		const node = document.createElement(tag);
		for (const [k, v] of Object.entries(attrs || {})) {
			if (v == null || v === false) continue;
			if (k === "class") node.className = v;
			else if (k === "dataset") Object.assign(node.dataset, v);
			else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
			else node.setAttribute(k, v === true ? "" : v);
		}
		for (const child of children.flat()) {
			if (child == null || child === false) continue;
			node.append(child instanceof Node ? child : String(child));
		}
		return node;
	}

	const playerUrl = (p) => `/spieler/${encodeURIComponent(p.minecraft || p.name)}`;

	/** Fallback-Bild (z.B. mc-heads.net), wenn der Server den Skin nicht kennt. */
	const fallbackUrl = (tpl, player, size) => tpl
		.replace("{uuid}", encodeURIComponent((player && player.uuid) || ""))
		.replace("{name}", encodeURIComponent((player && (player.minecraft || player.name)) || "MHF_Steve"))
		.replace("{size}", String(size));

	// ------------------------------------------------------------------ Skins
	// Der Mod liefert den Textur-Hash des echten Skins; die Website lädt die Skin-Datei über
	// /skin/<hash>.png und schneidet Kopf und Körper hier im Browser aus.

	const skinCache = new Map(); // hash -> Promise<{ head, body }>

	function renderSkin(hash, slim) {
		const key = hash + (slim ? ":slim" : "");
		if (!skinCache.has(key)) {
			skinCache.set(key, new Promise((resolve, reject) => {
				const img = new Image();
				img.crossOrigin = "anonymous";
				img.onload = () => {
					try {
						const hat = hasHatLayer(img);
						resolve({ head: drawHead(img, hat), body: drawBody(img, slim, hat) });
					} catch (err) {
						reject(err);
					}
				};
				img.onerror = reject;
				img.src = cfg.apiBase.replace(/\/$/, "") + "/skin/" + hash + ".png";
			}));
		}
		return skinCache.get(key);
	}

	function canvas(w, h) {
		const c = document.createElement("canvas");
		c.width = w;
		c.height = h;
		const ctx = c.getContext("2d");
		ctx.imageSmoothingEnabled = false;
		return [c, ctx];
	}

	/**
	 * Wie Minecraft: Ist bei alten 64×32-Skins der Hut-Bereich komplett deckend, gilt er als
	 * durchsichtig (sonst hätte z.B. Notch einen schwarzen Kasten auf dem Kopf).
	 */
	function hasHatLayer(img) {
		if (img.height !== 32) return true;
		const [, ctx] = canvas(32, 16);
		ctx.drawImage(img, 32, 0, 32, 16, 0, 0, 32, 16);
		const data = ctx.getImageData(0, 0, 32, 16).data;
		for (let i = 3; i < data.length; i += 4) if (data[i] < 128) return true;
		return false;
	}

	/** Gesicht (8×8) plus Hut-Ebene darüber. */
	function drawHead(img, hat) {
		const [c, ctx] = canvas(8, 8);
		ctx.drawImage(img, 8, 8, 8, 8, 0, 0, 8, 8);
		if (hat) ctx.drawImage(img, 40, 8, 8, 8, 0, 0, 8, 8);
		return c.toDataURL();
	}

	/** Vorderansicht des ganzen Körpers (16×32), mit allen Ebenen. Alte 64×32-Skins werden gespiegelt ergänzt. */
	function drawBody(img, slim, hat) {
		const [c, ctx] = canvas(16, 32);
		const legacy = img.height === 32;
		const arm = slim ? 3 : 4;
		const part = (sx, sy, w, h, dx, dy, mirror = false) => {
			if (!mirror) return ctx.drawImage(img, sx, sy, w, h, dx, dy, w, h);
			ctx.save();
			ctx.translate(dx + w, dy);
			ctx.scale(-1, 1);
			ctx.drawImage(img, sx, sy, w, h, 0, 0, w, h);
			ctx.restore();
		};
		// Grundebene
		part(8, 8, 8, 8, 4, 0);                                   // Kopf
		part(20, 20, 8, 12, 4, 8);                                // Oberkörper
		part(44, 20, arm, 12, 4 - arm, 8);                        // rechter Arm (links im Bild)
		part(4, 20, 4, 12, 4, 20);                                // rechtes Bein
		if (legacy) {
			part(44, 20, arm, 12, 12, 8, true);                   // linker Arm = gespiegelt
			part(4, 20, 4, 12, 8, 20, true);                      // linkes Bein = gespiegelt
		} else {
			part(36, 52, arm, 12, 12, 8);                         // linker Arm
			part(20, 52, 4, 12, 8, 20);                           // linkes Bein
		}
		// Zweite Ebene (Hut, Jacke, Ärmel, Hosen)
		if (hat) part(40, 8, 8, 8, 4, 0);
		if (!legacy) {
			part(20, 36, 8, 12, 4, 8);
			part(44, 36, arm, 12, 4 - arm, 8);
			part(52, 52, arm, 12, 12, 8);
			part(4, 36, 4, 12, 4, 20);
			part(4, 52, 4, 12, 8, 20);
		}
		return c.toDataURL();
	}

	function head(player, size = 32, lazy = true) {
		const img = el("img", { class: "head", alt: "", width: size, height: size, decoding: "async", loading: lazy ? "lazy" : null });
		const fallback = () => {
			img.addEventListener("error", () => img.classList.add("head--missing"), { once: true });
			img.src = fallbackUrl(cfg.headUrl, player, size * 2);
		};
		if (player && player.skin) {
			renderSkin(player.skin, player.slim).then((r) => (img.src = r.head), fallback);
		} else {
			fallback();
		}
		return img;
	}

	/** Ganzkörper-Bild für die Spielerseite. */
	function body(player, img) {
		const fallback = () => (img.src = fallbackUrl(cfg.bodyUrl, player, 270));
		if (player.skin) renderSkin(player.skin, player.slim).then((r) => (img.src = r.body), fallback);
		else fallback();
	}

	function playerLink(player, cls = "plink") {
		return el("a", { class: cls, href: playerUrl(player) }, player.name);
	}

	/**
	 * Feed-Nachricht: Spielernamen werden verlinkt, der Fortschritt in [Klammern] hervorgehoben.
	 * Alles läuft über Textknoten – kein innerHTML, also keine Injection über Spielernamen.
	 */
	function feedMessage(event) {
		const players = [event.player, event.killer].filter(Boolean);
		const p = el("p", { class: "feed__msg" });
		let text = event.message || "";
		const marker = event.type === "advancement" && event.title ? `[${event.title}]` : null;
		const parts = marker && text.includes(marker) ? text.split(marker) : [text];
		parts.forEach((part, i) => {
			if (i > 0) p.append(el("span", { class: "adv", dataset: { frame: event.frame || "task" }, title: event.description || "" }, event.title));
			p.append(...linkNames(part, players));
		});
		return p;
	}

	function linkNames(text, players) {
		const names = players.map((pl) => pl.minecraft || pl.name).filter(Boolean).sort((a, b) => b.length - a.length);
		if (names.length === 0) return [text];
		const escaped = names.map((n) => n.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
		const out = [];
		for (const piece of text.split(new RegExp(`(${escaped.join("|")})`))) {
			const player = players.find((pl) => (pl.minecraft || pl.name) === piece);
			out.push(player ? playerLink(player) : piece);
		}
		return out;
	}

	function feedItem(event, now) {
		const li = el("li", { class: `feed__item feed__item--${event.type}`, dataset: { frame: event.frame || "" } });
		li.append(
			el("a", { class: "feed__head", href: playerUrl(event.player), title: event.player.name, "aria-label": event.player.name }, head(event.player, 32)),
			el("div", { class: "feed__body" }, feedMessage(event), el("time", { class: "feed__time", datetime: event.at, title: new Date(event.at).toLocaleString("de-DE") }, fmt.when(event.at, now))),
			event.type === "death" ? el("span", { class: "feed__icon", "aria-hidden": "true" }, "☠") : el("span", { class: "feed__icon", "aria-hidden": "true" }, "★"),
		);
		return li;
	}

	async function getJson(path, signal) {
		const res = await fetch(api(path), { signal, headers: { accept: "application/json" } });
		if (!res.ok) {
			const err = new Error(`HTTP ${res.status}`);
			err.status = res.status;
			throw err;
		}
		return res.json();
	}

	/**
	 * Live-Verbindung über Server-Sent Events, mit Polling als Rückfall.
	 * handlers: { stats(snapshot), feed(list), event(item), status("live"|"poll"|"down"), server({serverOnline}) }
	 * status = Verbindung zur Website, snapshot.serverOnline = Verbindung der Website zum Minecraft-Server
	 */
	function connect(handlers) {
		let source = null;
		let pollTimer = null;
		let failures = 0;

		const poll = async () => {
			clearTimeout(pollTimer);
			try {
				const [stats, feed] = await Promise.all([getJson("/stats").catch((err) => (err.status === 503 ? null : Promise.reject(err))), getJson("/feed")]);
				if (stats) handlers.stats(stats);
				handlers.feed(feed.feed);
				handlers.status("poll");
				failures = 0;
			} catch {
				handlers.status("down");
				failures++;
			}
			pollTimer = setTimeout(() => (window.EventSource && failures === 0 ? open() : poll()), failures ? Math.min(60000, 5000 * failures) : 30000);
		};

		const open = () => {
			if (!window.EventSource) return poll();
			source = new EventSource(api("/live"));
			source.addEventListener("hello", (e) => {
				const data = JSON.parse(e.data);
				if (data.stats) handlers.stats(data.stats);
				handlers.feed(data.feed);
				handlers.status("live");
				failures = 0;
			});
			source.addEventListener("stats", (e) => handlers.stats(JSON.parse(e.data)));
			source.addEventListener("feed", (e) => handlers.feed(JSON.parse(e.data)));
			source.addEventListener("event", (e) => handlers.event(JSON.parse(e.data)));
			source.addEventListener("status", (e) => handlers.server && handlers.server(JSON.parse(e.data)));
			source.onerror = () => {
				// EventSource verbindet sich selbst neu; nach mehreren Fehlschlägen auf Polling wechseln
				handlers.status("down");
				if (++failures >= 3) {
					source.close();
					source = null;
					poll();
				}
			};
		};

		document.addEventListener("visibilitychange", () => {
			if (document.visibilityState === "visible" && !source) {
				failures = 0;
				clearTimeout(pollTimer);
				open();
			}
		});
		open();
	}

	const store = {
		get(key) {
			try { return JSON.parse(localStorage.getItem("mcstats:" + key)); } catch { return null; }
		},
		set(key, value) {
			try { localStorage.setItem("mcstats:" + key, JSON.stringify(value)); } catch { /* egal */ }
		},
	};

	function setTitle(stats) {
		const name = cfg.title || (stats && stats.serverName) || "";
		for (const node of document.querySelectorAll("[data-server-name]")) node.textContent = name || "Server-Stats";
	}

	document.addEventListener("DOMContentLoaded", () => {
		if (cfg.title) setTitle(null);
		const path = location.pathname.replace(/index\.html$/, "");
		for (const a of document.querySelectorAll(".nav a")) {
			const href = a.getAttribute("href");
			if (href === path || (href !== "/" && path.startsWith(href.replace(/\.html$/, "")))) a.setAttribute("aria-current", "page");
		}
	});

	window.MC = { cfg, api, fmt, metrics, el, head, body, playerLink, playerUrl, feedItem, getJson, connect, store, setTitle };
})();
