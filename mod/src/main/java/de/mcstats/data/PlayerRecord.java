package de.mcstats.data;

import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.Set;
import java.util.UUID;

/**
 * Unveränderlicher Stand eines Spielers.
 *
 * @param stats           gezählte Stats (nach einem Reset: nur was seitdem dazukam)
 * @param lastSeen        Zeitpunkt (ms), zu dem der Spieler zuletzt online war bzw. die Datei geschrieben wurde
 * @param rawStats        unveränderte Stats aus world/players/stats/&lt;uuid&gt;.json (Feld "stats")
 * @param rawAdvancements alle erledigten Fortschritte (mit Anzeige)
 */
public record PlayerRecord(UUID uuid, String name, EnumMap<Metric, Long> metrics, JsonObject stats, long lastSeen,
		JsonObject rawStats, Set<String> rawAdvancements) {
	public long get(Metric metric) {
		return metrics.getOrDefault(metric, 0L);
	}
}
