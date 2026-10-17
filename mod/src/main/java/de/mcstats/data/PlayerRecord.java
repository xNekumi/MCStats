package de.mcstats.data;

import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.UUID;

/**
 * Unveränderlicher Stand eines Spielers.
 *
 * @param stats    das Roh-JSON aus world/stats/&lt;uuid&gt;.json (Feld "stats")
 * @param lastSeen Zeitpunkt (ms), zu dem der Spieler zuletzt online war bzw. die Datei geschrieben wurde
 */
public record PlayerRecord(UUID uuid, String name, EnumMap<Metric, Long> metrics, JsonObject stats, long lastSeen) {
	public long get(Metric metric) {
		return metrics.getOrDefault(metric, 0L);
	}
}
