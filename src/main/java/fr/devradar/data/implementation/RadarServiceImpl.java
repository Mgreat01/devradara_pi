package fr.devradar.data.implementation;

import fr.devradar.domain.model.Radar;
import fr.devradar.domain.service.RadarService;
import fr.devradar.data.repository.GitHubClient;
import fr.devradar.data.repository.SnapshotRepository;
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
public class RadarServiceImpl implements RadarService {
    private final GitHubClient github;
    private final SnapshotRepository snapshots;
    private final Cache<String, Radar> cache = Caffeine.newBuilder()
            .maximumSize(1000).expireAfter(new com.github.benmanes.caffeine.cache.Expiry<String, Radar>() {
                public long expireAfterCreate(String key, Radar value, long time) { return Duration.ofSeconds(value.coverage().complete() ? 900 : 30).toNanos(); }
                public long expireAfterUpdate(String key, Radar value, long time, long duration) { return expireAfterCreate(key, value, time); }
                public long expireAfterRead(String key, Radar value, long time, long duration) { return duration; }
            }).build();
    public RadarServiceImpl(GitHubClient github, SnapshotRepository snapshots) {
        this.github = github;
        this.snapshots = snapshots;
    }
    public Radar find(String username) {
        if (!username.matches("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,37}[a-zA-Z0-9])?"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pseudo GitHub invalide (1 à 39 caractères)");
        return cache.get(username.toLowerCase(Locale.ROOT), this::load);
    }
    private Radar load(String username) {
        return collect(username);
    }
    private Radar collect(String username) {
        var user = github.user(username);
        if (user == null || !user.isObject() || user.path("login").asText("").isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Le profil reçu est incomplet.");
        boolean reposAvailable = true, eventsAvailable = true;
        List<JsonNode> allRepos = List.of(), rawEvents = List.of();
        try { allRepos = github.repositories(username); } catch (ResponseStatusException ex) { reposAvailable = false; }
        try { rawEvents = github.events(username); } catch (ResponseStatusException ex) { eventsAvailable = false; }
        var repos = allRepos.stream().filter(r -> !r.path("fork").asBoolean()).toList();
        Instant now = Instant.now();
        var invalidEvent = new java.util.concurrent.atomic.AtomicBoolean();
        var events = rawEvents.stream().filter(e -> {
            try { return !Instant.parse(e.path("created_at").asText()).isBefore(now.minus(Duration.ofDays(30))); }
            catch (DateTimeException ex) { invalidEvent.set(true); return false; }
        }).toList();
        if (invalidEvent.get()) eventsAvailable = false;
        long stars = repos.stream().mapToLong(r -> r.path("stargazers_count").asLong()).sum();
        var counts = repos.stream().filter(r -> !r.path("language").asText("").isBlank())
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
                    "Activité privée exclue ; série calculée en UTC jusqu’à aujourd’hui ou hier.",
                    reposAvailable && eventsAvailable ? "Collecte disponible dans les limites indiquées." : "Collecte partielle : certaines données et composantes du score sont indisponibles."),
                new Radar.Profile(user.path("bio").asText(""), user.path("location").asText(""), user.path("blog").asText(""),
                    user.path("followers").asInt(), user.path("following").asInt(), user.path("created_at").asText("")),
                new Radar.Coverage(reposAvailable, eventsAvailable, allRepos.size() >= 500, rawEvents.size() >= 300));
        if (radar.coverage().complete()) {
            try {
                snapshots.save(radar);
                snapshots.observe(radar, allRepos.stream().map(r -> r.path("id").asLong()).filter(id -> id > 0).toList());
            }
            catch (org.springframework.dao.DataAccessException ex) {
                org.slf4j.LoggerFactory.getLogger(RadarServiceImpl.class).warn("Observation non enregistrée : stockage indisponible");
            }
        }
        return radar;
    }
}
