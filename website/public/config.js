// Einstellungen im Browser. Läuft die Seite über server.js, bleibt apiBase leer.
window.MCSTATS_CONFIG = {
	// Adresse des Website-Servers, falls die HTML-Dateien woanders liegen (leer = gleiche Adresse)
	apiBase: "",
	// Überschreibt den serverName aus der Mod-Konfiguration (leer = vom Mod übernehmen)
	title: "",
	// Die Köpfe werden aus dem echten Skin gerendert, den der Mod vom Server kennt.
	// Nur wenn ein Spieler seit der Installation noch nie online war, kommt das Bild von hier.
	// Platzhalter: {name}, {uuid}, {size}. Auf Offline-Mode-Servern {name} verwenden.
	headUrl: "https://mc-heads.net/avatar/{name}/{size}",
	bodyUrl: "https://mc-heads.net/body/{name}/{size}",
	// Welche Bestenlisten auf der Startseite erscheinen und in welcher Reihenfolge.
	boards: ["playtime", "diamonds", "deaths", "blocksMined", "mobKills", "distance", "totems", "damageDealt", "foodEaten", "playerKills", "advancements", "fishCaught"],
};
