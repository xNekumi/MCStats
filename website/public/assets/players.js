/* Liste aller Spieler mit Suche und Sortierung. */
(() => {
	const { fmt, el, head, playerUrl, getJson, setTitle } = window.MC;
	const $ = (id) => document.getElementById(id);
	let players = [];

	function render() {
		const q = $("q").value.trim().toLowerCase();
		const sort = $("sort").value;
		const list = players.filter((p) => !q || p.name.toLowerCase().includes(q) || p.minecraft.toLowerCase().includes(q));
		list.sort((a, b) => {
			if (a.online !== b.online && sort !== "name") return a.online ? -1 : 1;
			if (sort === "name") return a.name.localeCompare(b.name, "de");
			if (sort === "lastSeen") return b.lastSeen.localeCompare(a.lastSeen);
			return b.playtime - a.playtime;
		});
		$("roster").replaceChildren(...list.map((p) => el("li", {},
			el("a", { class: "roster__item", href: playerUrl(p) },
				head(p, 40),
				el("span", { class: "roster__name" }, p.name, el("small", {}, p.online ? "online" : `zuletzt ${fmt.ago(p.lastSeen)}`)),
				el("span", { class: "roster__val num" }, fmt.duration(p.playtime)),
				p.online ? el("span", { class: "pulse pulse--on", role: "img", "aria-label": "online" }) : null))));
		$("empty").hidden = list.length > 0;
	}

	async function load() {
		try {
			const [data, stats] = await Promise.all([getJson("/players"), getJson("/stats").catch(() => null)]);
			setTitle(stats);
			players = data.players;
			const online = players.filter((p) => p.online).length;
			$("summary").textContent = data.serverOnline === false
				? `${fmt.num(players.length)} Spieler waren schon auf dem Server. Der Server ist gerade offline.`
				: `${fmt.num(players.length)} Spieler waren schon auf dem Server, ${fmt.num(online)} ${online === 1 ? "ist" : "sind"} gerade online.`;
			render();
		} catch {
			$("banner-title").textContent = "Der Server antwortet gerade nicht.";
			$("banner-text").textContent = "Versuch es in ein paar Minuten noch einmal.";
			$("banner").hidden = false;
		}
	}

	$("q").addEventListener("input", render);
	$("sort").addEventListener("change", render);
	load();
})();
