/* Startseite: Überblick, Online-Liste, Feed, Bestenlisten. */
(() => {
	const { cfg, fmt, metrics, el, head, playerLink, playerUrl, feedItem, connect, store, setTitle } = window.MC;
	const $ = (id) => document.getElementById(id);
	const main = $("main");

	let snapshot = null;
	let feed = [];
	let lastStatus = "loading";
	let factPool = [];
	let factKey = null;

	// ------------------------------------------------------------------ Status

	function setStatus(state) {
		lastStatus = state;
		const text = $("status-text");
		const banner = $("banner");
		const since = snapshot && (snapshot.lastContact || snapshot.updatedAt);
		if (state === "down") {
			main.dataset.state = "down";
			text.textContent = snapshot ? "Keine Verbindung zur Website · zeige gespeicherten Stand" : "Keine Verbindung";
			showBanner("Die Stats sind gerade nicht erreichbar.", "Wir versuchen es automatisch weiter.");
		} else if (snapshot && snapshot.serverOnline === false) {
			main.dataset.state = "down";
			text.textContent = since ? `Server offline · letzter Stand ${fmt.ago(since)}` : "Server offline";
			showBanner("Der Minecraft-Server ist gerade offline.",
				`Neustart, Wartung oder Feierabend. Die Zahlen unten sind der letzte Stand${since ? " von " + fmt.when(since) : ""}.`);
		} else if (state === "live" || state === "poll") {
			main.dataset.state = state;
			text.textContent = state === "live" ? "Server online · live" : `Server online · aktualisiert ${fmt.ago(snapshot ? snapshot.updatedAt : Date.now())}`;
			banner.hidden = true;
		}
	}

	function showBanner(title, body) {
		$("banner-title").textContent = title;
		$("banner-text").textContent = body;
		$("banner").hidden = false;
	}

	// ------------------------------------------------------------------ Überblick

	function renderSince(s) {
		const node = $("since");
		node.hidden = !s.countingSince;
		if (s.countingSince) node.textContent = `Gezählt wird seit ${fmt.date(s.countingSince)}`;
	}

	function renderFigures(s) {
		$("fig-online").textContent = fmt.num(s.onlineCount);
		$("fig-online-sub").textContent = s.live && s.live.length ? `${s.live.length} live auf Twitch` : `von max. ${fmt.num(s.maxPlayers)}`;
		$("fig-players").textContent = fmt.num(s.playerCount);
		$("fig-hours").textContent = fmt.hours(s.totals.playtime);
		$("fig-deaths").textContent = fmt.num(s.totals.deaths);
		const perHour = s.totals.playtime > 3600 ? s.totals.deaths / (s.totals.playtime / 3600) : 0;
		$("fig-deaths-sub").textContent = perHour ? `≈ ${perHour.toLocaleString("de-DE", { maximumFractionDigits: 1 })} pro Spielstunde` : " ";
	}

	function renderOnline(s) {
		const list = $("online");
		$("online-count").textContent = s.online.length ? `(${s.online.length})` : "";
		list.replaceChildren(...s.online.map((p) => {
			const children = [head(p, 28, false), el("span", {}, p.name)];
			if (p.live) {
				children.push(el("span", { class: "livetag", title: p.live.title }, el("span", { class: "dot" }), fmt.num(p.live.viewers)));
				return el("li", {}, el("a", { class: "chip chip--live", href: `https://www.twitch.tv/${encodeURIComponent(p.live.login)}`, target: "_blank", rel: "noopener", title: `${p.name} streamt: ${p.live.title}` }, children));
			}
			return el("li", {}, el("a", { class: "chip", href: playerUrl(p) }, children));
		}));
		const empty = $("online-empty");
		empty.hidden = s.online.length > 0;
		empty.textContent = s.serverOnline === false ? "Der Server ist offline – also ist auch niemand drauf." : "Gerade ist niemand online. Die Welt gehört den Creepern.";
	}

	function renderBoards(s) {
		const keys = (cfg.boards && cfg.boards.length ? cfg.boards : Object.keys(s.boards)).filter((k) => metrics[k] && s.boards[k]);
		$("boards").replaceChildren(...keys.map((key) => {
			const m = metrics[key];
			const rows = s.boards[key].slice(0, 10);
			const list = el("ol", { class: "board__list" });
			if (rows.length === 0) list.append(el("li", { class: "board__none" }, "Noch niemand."));
			rows.forEach((p, i) => list.append(el("li", { class: "board__row", dataset: { rank: i + 1 } },
				el("span", { class: "board__rank num" }, i + 1),
				head(p, 24),
				el("span", { class: "board__name" }, playerLink(p)),
				el("span", { class: "board__val num" }, m.unit(p.value)))));
			return el("article", { class: "card board", dataset: { metric: key } },
				el("header", { class: "card__head" }, el("h3", {}, m.title), el("p", { class: "card__sub" }, m.sub)),
				list);
		}));
	}

	// ------------------------------------------------------------------ Fakten

	function buildFacts(s) {
		const facts = [];
		const top = (k) => (s.boards[k] || [])[0];
		const add = (key, player, text) => player && facts.push({ key, player, text });
		const pt = top("playtime");
		if (pt) add("pt", pt, ["", ` hat schon ${fmt.duration(pt.value)} auf dem Server verbracht – mehr als alle anderen.`]);
		const dist = top("distance");
		if (dist && dist.value > 1000) add("dist", dist, ["", ` ist bereits ${fmt.distance(dist.value)} gereist. Das sind etwa ${fmt.num(dist.value / 0.8)} Schritte.`]);
		const dia = top("diamonds");
		if (dia) add("dia", dia, ["Niemand hat so viele Diamanterze abgebaut wie ", `: ${fmt.num(dia.value)} Stück.`]);
		const deaths = top("deaths");
		if (deaths && deaths.value > 1) add("deaths", deaths, ["", ` ist schon ${fmt.num(deaths.value)}-mal gestorben. Übung macht den Meister?`]);
		const totem = top("totems");
		if (totem) add("totem", totem, ["", ` wurde ${fmt.num(totem.value)}-mal von einem Totem gerettet.`]);
		const food = top("foodEaten");
		if (food && food.value > 50) add("food", food, ["", ` hat ${fmt.num(food.value)} Mahlzeiten verputzt. Guten Appetit!`]);
		const blocks = top("blocksMined");
		if (blocks) add("blocks", blocks, ["", ` hat ${fmt.num(blocks.value)} Blöcke abgebaut – irgendwo fehlt jetzt ein Berg.`]);
		const pk = top("playerKills");
		if (pk) add("pk", pk, ["Vorsicht vor ", `: ${fmt.num(pk.value)} besiegte Mitspieler.`]);
		const fish = top("fishCaught");
		if (fish && fish.value > 10) add("fish", fish, ["", ` hat ${fmt.num(fish.value)} Fische an Land gezogen.`]);
		if (s.totals.blocksMined > 0 && s.playerCount > 1) add("all", { name: "Alle zusammen", uuid: null, all: true }, ["", ` haben ${fmt.num(s.totals.blocksMined)} Blöcke abgebaut und ${fmt.distance(s.totals.distance)} zurückgelegt.`]);
		return facts;
	}

	function showFact(force) {
		const box = $("fact");
		if (factPool.length === 0) { box.hidden = true; return; }
		let fact = force ? null : factPool.find((f) => f.key === factKey);
		if (!fact) {
			const others = factPool.filter((f) => f.key !== factKey);
			const pool = others.length ? others : factPool;
			fact = pool[Math.floor(Math.random() * pool.length)];
		}
		factKey = fact.key;
		const name = fact.player.all ? el("strong", {}, fact.player.name) : playerLink(fact.player, "plink plink--strong");
		$("fact-text").replaceChildren(fact.player.all ? "" : head(fact.player, 28), fact.text[0], name, fact.text[1]);
		$("fact-next").hidden = factPool.length < 2;
		box.hidden = false;
	}

	// ------------------------------------------------------------------ Feed

	function renderFeed() {
		const list = $("feed");
		const now = Date.now();
		list.replaceChildren(...feed.slice(0, 12).map((e) => feedItem(e, now)));
		const empty = $("feed-empty");
		empty.hidden = feed.length > 0;
		empty.textContent = "Noch ist nichts passiert. Das ändert sich bestimmt bald.";
	}

	function onEvent(event) {
		feed = [event, ...feed].slice(0, 50);
		renderFeed();
		const first = $("feed").firstElementChild;
		if (first) first.classList.add("is-new");
	}

	// ------------------------------------------------------------------ Start

	function onStats(s) {
		snapshot = s;
		store.set("snapshot", s);
		setTitle(s);
		renderSince(s);
		renderFigures(s);
		renderOnline(s);
		renderBoards(s);
		factPool = buildFacts(s);
		showFact(false);
		if (lastStatus !== "loading") setStatus(lastStatus);
	}

	function init() {
		$("fact-next").addEventListener("click", () => showFact(true));
		// Letzten Stand sofort zeigen, bis die Verbindung steht
		const cached = store.get("snapshot");
		const cachedFeed = store.get("feed");
		if (cached && cached.boards) onStats(cached);
		if (Array.isArray(cachedFeed)) { feed = cachedFeed; renderFeed(); }

		connect({
			stats: onStats,
			feed: (list) => { feed = list || []; store.set("feed", feed.slice(0, 20)); renderFeed(); },
			event: (e) => { onEvent(e); store.set("feed", feed.slice(0, 20)); },
			status: setStatus,
			server: (info) => {
				if (snapshot) Object.assign(snapshot, info);
				setStatus(lastStatus);
			},
		});
		// relative Zeiten aktuell halten
		setInterval(() => {
			if (document.visibilityState !== "visible") return;
			renderFeed();
			setStatus(lastStatus);
		}, 30000);
	}

	init();
})();
