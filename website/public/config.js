// Einstellungen im Browser. Läuft die Seite über server.js, bleibt apiBase leer.
window.MCSTATS_CONFIG = {
	// Adresse des Website-Servers, falls die HTML-Dateien woanders liegen (leer = gleiche Adresse)
	apiBase: "",
	// Überschreibt den serverName aus der Mod-Konfiguration (leer = vom Mod übernehmen)
	title: "",
	// Kopf- und Körper-Renderings der Skins. {uuid} und {size} werden ersetzt.
	headUrl: "https://mc-heads.net/avatar/{uuid}/{size}",
	bodyUrl: "https://mc-heads.net/body/{uuid}/{size}",
	// Welche Bestenlisten auf der Startseite erscheinen und in welcher Reihenfolge.
	boards: ["playtime", "diamonds", "deaths", "blocksMined", "mobKills", "distance", "totems", "damageDealt", "foodEaten", "playerKills", "advancements", "fishCaught"],
};
