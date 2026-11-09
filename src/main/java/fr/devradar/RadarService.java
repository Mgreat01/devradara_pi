package fr.devradar;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RadarService {
    private final GitHubClient github;
    private final SnapshotStore snapshots;
    private final Cache<String, Radar> cache = Caffeine.newBuilder()
            .maximumSize(1000).expireAfterWrite(Duration.ofMinutes(15)).build();
    public RadarService(GitHubClient github, SnapshotStore snapshots) {
        this.github = github;
        this.snapshots = snapshots;
    }
    public Radar find(String username) {
        if (!username.matches("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,37}[a-zA-Z0-9])?"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pseudo GitHub invalide (1 à 39 caractères)");
        return cache.get(username.toLowerCase(Locale.ROOT), this::load);
    }
    private Radar load(String username) {
        var user = github.user(username);
        var repos = github.repositories(username).stream().filter(r -> !r.path("fork").asBoolean()).toList();
        Instant now = Instant.now();
        var events = github.events(username).stream().filter(e ->
            !Instant.parse(e.path("created_at").asText()).isBefore(now.minus(Duration.ofDays(30)))).toList();
        long stars = repos.stream().mapToLong(r -> r.path("stargazers_count").asLong()).sum();
        var counts = repos.stream().filter(r -> !r.path("language").isNull())
                .collect(Collectors.groupingBy(r -> r.path("language").asText(), Collectors.counting()));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        var languages = counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new Radar.Language(e.getKey(), Math.round(e.getValue() * 1000.0 / total) / 10.0)).toList();
        var top = repos.stream().sorted(Comparator.comparingLong((JsonNode r) -> r.path("stargazers_count").asLong()).reversed())
                .limit(6).map(r -> new Radar.Repo(r.path("name").asText(), r.path("html_url").asText(),
                        r.path("description").asText(""), r.path("language").asText(""), r.path("stargazers_count").asLong())).toList();
        var dates = events.stream().map(e -> Instant.parse(e.path("created_at").asText()).atZone(ZoneOffset.UTC).toLocalDate())
                .collect(Collectors.toSet());
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        if (!dates.contains(day)) day = day.minusDays(1);
        int streak = 0;
        while (dates.contains(day)) { streak++; day = day.minusDays(1); }
        var breakdown = new Radar.ScoreBreakdown(Math.min(25, repos.size() * 2),
                (int) Math.min(25, Math.round(5 * Math.log10(1.0 + stars))),
                Math.min(25, events.size()), repos.isEmpty() ? 0 :
                (int) Math.round(25.0 * repos.stream().filter(r -> !r.path("description").asText("").isBlank()).count() / repos.size()));
        var radar = new Radar(user.path("login").asText(), user.path("name").asText(username),
                user.path("avatar_url").asText(), user.path("html_url").asText(),
                breakdown.repositories() + breakdown.community() + breakdown.activity() + breakdown.documentation(),
                breakdown, stars, user.path("public_repos").asInt(), events.size(), streak, languages, top, now,
                List.of("Signal de visibilité publique, pas une mesure de compétence ou un classement de candidats.",
                    "Événements publics observés sur 30 jours, maximum 300 ; ce ne sont pas des commits.",
                    "Langages : répartition du langage principal des 500 dépôts récents au maximum, hors forks.",
                    "Activité privée exclue ; série calculée en UTC jusqu’à aujourd’hui ou hier."));
        snapshots.save(radar);
        return radar;
    }
}
