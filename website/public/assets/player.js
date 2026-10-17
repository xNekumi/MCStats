/* Spielerseite: /spieler/<Name> oder /player.html?p=<UUID|Name> */
(() => {
	const { fmt, metrics, el, head, body, feedItem, getJson, setTitle } = window.MC;
	const $ = (id) => document.getElementById(id);

	const fromPath = location.pathname.match(/^\/spieler\/([^/]+)/);
	const id = fromPath ? decodeURIComponent(fromPath[1]) : new URLSearchParams(location.search).get("p");

	const ORDER = ["playtime", "deaths", "diamonds", "blocksMined", "mobKills", "playerKills", "distance", "advancements", "damageDealt", "damageTaken", "totems", "foodEaten", "jumps", "fishCaught", "trades", "animalsBred", "itemsCrafted"];

	function customValue(id, value) {
		const key = id.replace(/^minecraft:/, "");
		if (key.endsWith("_one_cm")) return fmt.distance(value / 100);
		if (/(play_time|total_world_time|time_since_|crouch_time|sneak_time)/.test(key)) return fmt.duration(value / 20);
		if (key.startsWith("damage_")) return fmt.hearts(value);
		return fmt.num(value);
	}

	function statusLine(p) {
		return [el("span", { class: p.online ? "pulse pulse--on" : "pulse", "aria-hidden": "true" }), p.online ? "Gerade online" : `Zuletzt online ${fmt.ago(p.lastSeen)}`];
	}

	function banner(title, text) {
		$("banner-title").textContent = title;
		$("banner-text").textContent = text;
		$("banner").hidden = false;
	}

	function render(p) {
		document.title = `${p.name} · Spieler-Stats`;
		$("p-name").textContent = p.name;
		const bodyImg = $("p-body");
		bodyImg.addEventListener("error", () => bodyImg.replaceWith(head(p, 96, false)), { once: true });
		body(p, bodyImg);

		$("p-status").replaceChildren(...statusLine(p));
		if (p.serverOnline === false) banner("Der Minecraft-Server ist gerade offline.", `Du siehst den zuletzt gespeicherten Stand${p.lastContact ? " von " + fmt.when(p.lastContact) : ""}.`);
		const m = p.metrics;
		$("p-summary").textContent = `${fmt.duration(m.playtime)} gespielt · ${fmt.num(m.deaths)} ${m.deaths === 1 ? "Tod" : "Tode"} · ${fmt.num(m.advancements)} Fortschritte`
			+ (p.countingSince ? ` (seit ${fmt.date(p.countingSince)})` : "");
		if (p.live) {
			$("p-twitch").replaceChildren(el("a", { class: "chip chip--live", href: `https://www.twitch.tv/${encodeURIComponent(p.live.login)}`, target: "_blank", rel: "noopener" },
				el("span", { class: "livetag" }, el("span", { class: "dot" }), "LIVE"), p.live.title));
		} else if (p.twitch) {
			$("p-twitch").replaceChildren(el("a", { href: `https://www.twitch.tv/${encodeURIComponent(p.twitch)}`, target: "_blank", rel: "noopener" }, `twitch.tv/${p.twitch}`));
		}
		$("profile").hidden = false;

		$("metrics").replaceChildren(...ORDER.filter((k) => metrics[k]).map((k) => {
			const rank = p.ranks[k];
			return el("div", { class: "metric", dataset: { top: rank && rank <= 3 ? rank : "" } },
				el("dt", {}, metrics[k].short),
				el("dd", { class: "num" }, metrics[k].unit(m[k] || 0)),
				el("small", {}, rank ? `Platz ${rank} von ${p.playerCount}` : "noch nicht gewertet"));
		}));
		$("metrics-section").hidden = false;

		const tops = [
			["mined", "Am meisten abgebaut"],
			["killed", "Am liebsten erlegt"],
			["killedBy", "Gestorben durch"],
			["used", "Am meisten benutzt"],
			["crafted", "Am meisten hergestellt"],
		].filter(([k]) => p[k] && p[k].length);
		$("tops").replaceChildren(...tops.map(([k, title]) => {
			const max = p[k][0].value || 1;
			return el("section", { class: "card top" },
				el("header", { class: "card__head" }, el("h2", {}, title)),
				el("ol", { class: "bars" }, p[k].map((row) => el("li", { class: "bar", style: `--w:${Math.max(3, (row.value / max) * 100).toFixed(1)}%` },
					el("span", { class: "bar__name" }, row.name),
					el("span", { class: "bar__val num" }, fmt.num(row.value))))));
		}));

		const now = Date.now();
		$("feed").replaceChildren(...p.feed.map((e) => feedItem(e, now)));
		$("feed-empty").hidden = p.feed.length > 0;

		const rows = p.custom.slice().sort((a, b) => a.name.localeCompare(b.name, "de"));
		const renderAll = () => {
			const q = $("all-filter").value.trim().toLowerCase();
			$("all").replaceChildren(...rows.filter((r) => !q || r.name.toLowerCase().includes(q)).map((r) =>
				el("tr", {}, el("th", { scope: "row" }, r.name), el("td", { class: "num" }, customValue(r.id, r.value)))));
		};
		$("all-filter").addEventListener("input", renderAll);
		renderAll();
		$("details").hidden = false;
	}

	async function load() {
		if (!id) {
			banner("Kein Spieler angegeben.", "Such dir einen aus der Liste aus.");
			return;
		}
		try {
			const [player, stats] = await Promise.all([getJson(`/player/${encodeURIComponent(id)}`), getJson("/stats").catch(() => null)]);
			setTitle(stats);
			render(player);
		} catch (err) {
			if (err.status === 404) banner("Diesen Spieler kennen wir nicht.", `„${id}“ war entweder noch nie auf dem Server oder hat sich umbenannt.`);
			else banner("Der Server antwortet gerade nicht.", "Versuch es in ein paar Minuten noch einmal.");
		}
	}

	load();
	setInterval(() => {
		if (document.visibilityState !== "visible" || $("profile").hidden) return;
		getJson(`/player/${encodeURIComponent(id)}`).then((p) => $("p-status").replaceChildren(...statusLine(p))).catch(() => {});
	}, 60000);
})();
