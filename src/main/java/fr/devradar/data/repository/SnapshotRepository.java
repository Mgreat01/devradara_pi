package fr.devradar.data.repository;

import fr.devradar.domain.model.Radar;
import fr.devradar.domain.model.Evolution;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SnapshotRepository implements fr.devradar.domain.service.EvolutionService {
    private final JdbcTemplate jdbc;
    public SnapshotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public void save(Radar radar) {
        int changed = jdbc.update("update radar_snapshot set score = ?, observed_at = ? where username = ?",
                radar.score(), java.sql.Timestamp.from(radar.cachedAt()), radar.username());
        if (changed == 0) jdbc.update("insert into radar_snapshot(username, score, observed_at) values (?, ?, ?)",
                radar.username(), radar.score(), java.sql.Timestamp.from(radar.cachedAt()));
    }
    private final ObjectMapper json = new ObjectMapper();
    private long lastCleanup;
    /** Une observation par jour, 365 jours et 5 000 lignes au total, sans tâche planifiée. */
    public synchronized void observe(Radar radar, List<Long> repositoryIds) {
        if (!radar.coverage().complete()) return;
        long now = System.currentTimeMillis();
        if (now - lastCleanup > 3_600_000) {
            jdbc.update("delete from profile_observation where observed_at < ?", java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(365))));
            lastCleanup = now;
        }
        String name = radar.username().toLowerCase(Locale.ROOT);
        var day = java.sql.Date.valueOf(radar.cachedAt().atZone(ZoneOffset.UTC).toLocalDate());
        if (jdbc.queryForObject("select count(*) from profile_observation where username = ? and observation_day = ?", Integer.class, name, day) > 0) return;
        if (jdbc.queryForObject("select count(*) from profile_observation", Integer.class) >= 5000) return;
        try {
            String languages = json.writeValueAsString(radar.languages());
            String ids = json.writeValueAsString(repositoryIds.stream().distinct().limit(500).toList());
            if (languages.length() > 8192 || ids.length() > 8192) return;
            jdbc.update("insert into profile_observation(username, observation_day, observed_at, score, stars, repositories, events, languages_json, repository_ids_json, truncated) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                name, day, java.sql.Timestamp.from(radar.cachedAt()), radar.score(), radar.stars(), radar.publicRepos(), radar.observedEvents30Days(),
                languages, ids, radar.coverage().repositoriesTruncated());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Observation invalide", ex); }
    }
    public Evolution history(String username, int days) {
        var name = username.toLowerCase(Locale.ROOT);
        var from = java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(days == 0 ? 365 : days)));
        var first = jdbc.query("select min(observed_at) from profile_observation where lower(username) = ?", rs -> {
            rs.next(); var value = rs.getTimestamp(1); return value == null ? null : value.toInstant();
        }, name);
        var previous = new HashSet<Long>();
        var points = new ArrayList<Evolution.Point>();
        jdbc.query("select * from profile_observation where lower(username) = ? and observed_at >= ? order by observed_at limit 366", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            try {
                List<Long> ids = json.readValue(rs.getString("repository_ids_json"), new TypeReference<List<Long>>() {});
                var current = new HashSet<>(ids);
                Integer added = points.isEmpty() ? null : (int) current.stream().filter(id -> !previous.contains(id)).count();
                previous.clear(); previous.addAll(current);
                points.add(new Evolution.Point(rs.getTimestamp("observed_at").toInstant(), rs.getInt("score"), rs.getLong("stars"),
                    rs.getInt("repositories"), rs.getInt("events"), json.readValue(rs.getString("languages_json"), new TypeReference<List<Radar.Language>>() {}), added, rs.getBoolean("truncated")));
            } catch (java.io.IOException ex) { throw new java.sql.SQLException("Observation illisible", ex); }
        }, name, from);
        return new Evolution(username, first, 365, true, points,
            "Observations faites lors des consultations, au plus une par jour. Aucune collecte en arrière-plan. Nouveaux dépôts = identifiants nouvellement observés, pas nécessairement nouvellement créés. Rétention 365 jours, plafond global 5 000 observations ; des jours peuvent manquer.");
    }
}
