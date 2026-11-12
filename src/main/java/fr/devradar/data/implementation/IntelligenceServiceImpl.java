package fr.devradar.data.implementation;

import fr.devradar.domain.model.RepositoryIntelligence;
import fr.devradar.domain.model.RepositoryIntelligence.*;
import fr.devradar.domain.service.IntelligenceService;
import fr.devradar.data.repository.GitHubClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.nio.charset.StandardCharsets;

@Service
public class IntelligenceServiceImpl implements IntelligenceService {
    private final GitHubClient github;
    private final Semaphore gate = new Semaphore(1);
    private final Cache<String, RepositoryIntelligence> cache = Caffeine.newBuilder().maximumSize(200)
        .expireAfter(new Expiry<String, RepositoryIntelligence>() {
            public long expireAfterCreate(String key, RepositoryIntelligence value, long now) { return Duration.ofMinutes(value.complete() ? 1440 : 5).toNanos(); }
            public long expireAfterUpdate(String key, RepositoryIntelligence value, long now, long duration) { return expireAfterCreate(key, value, now); }
            public long expireAfterRead(String key, RepositoryIntelligence value, long now, long duration) { return duration; }
        }).build();
    private static final List<String> MANIFESTS = List.of("pom.xml", "package.json", "pubspec.yaml", "build.gradle", "build.gradle.kts", "requirements.txt", "pyproject.toml", "Cargo.toml");
    public IntelligenceServiceImpl(GitHubClient github) { this.github = github; }
    public RepositoryIntelligence repository(String owner, String repo) {
        if (!owner.matches("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,37}[a-zA-Z0-9])?") ||
            !repo.matches("[a-zA-Z0-9_.-]{1,100}") || repo.equals(".") || repo.equals(".."))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nom de projet invalide.");
        return cache.get((owner + "/" + repo).toLowerCase(Locale.ROOT), key -> load(owner, repo));
    }
    public List<RepositoryIntelligence> known(String username) {
        return cache.asMap().values().stream().filter(r -> r.owner().equalsIgnoreCase(username))
            .sorted(Comparator.comparing(RepositoryIntelligence::name)).toList();
    }
    public List<RepositoryIntelligence> observed() { return List.copyOf(cache.asMap().values()); }
    private RepositoryIntelligence load(String owner, String repo) {
        if (!gate.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Une analyse est déjà en cours. Réessayez dans un instant.");
        try { return analyze(owner, repo); } finally { gate.release(); }
    }
    private RepositoryIntelligence analyze(String owner, String repo) {
        var metadata = github.repository(owner, repo);
        Instant at = Instant.now();
        String ref = metadata.path("default_branch").asText("main");
        boolean complete = true;
        var warnings = new ArrayList<String>();
        var entries = new HashMap<String, JsonNode>();
        try {
            var tree = github.tree(owner, repo, ref);
            ref = tree.path("sha").asText(ref);
            complete = !tree.path("truncated").asBoolean() && tree.path("tree").isArray() && tree.path("tree").size() <= 2000;
            int count = 0;
            for (var entry : tree.path("tree")) {
                if (++count > 2000) break;
                if (entry.path("type").asText().equals("blob")) entries.put(entry.path("path").asText(), entry);
            }
        } catch (ResponseStatusException ex) { complete = false; warnings.add("La liste complète des fichiers n’a pas pu être consultée."); }
        boolean treeComplete = complete;
        var detections = new ArrayList<Detection>();
        int inspected = 0;
        for (String path : MANIFESTS) {
            var entry = entries.get(path);
            if (entry == null) continue;
            if (++inspected > 3) { complete = false; warnings.add("Seuls trois manifestes à la racine sont analysés."); break; }
            if (entry.path("size").asLong() > 102400) { complete = false; warnings.add(path + " dépasse la taille d’analyse autorisée."); continue; }
            try {
                var blob = github.blob(owner, repo, entry.path("sha").asText());
                if (!blob.path("encoding").asText().equals("base64") || blob.path("size").asLong() > 102400) throw new IllegalArgumentException();
                byte[] decoded = Base64.getMimeDecoder().decode(blob.path("content").asText());
                if (decoded.length > 102400) throw new IllegalArgumentException();
                detections.addAll(TechnologyDetector.detect(path, new String(decoded, StandardCharsets.UTF_8), owner + "/" + repo, at, ref));
            } catch (ResponseStatusException | IllegalArgumentException ex) { complete = false; warnings.add(path + " n’a pas pu être analysé."); }
        }
        if (entries.containsKey("Dockerfile")) detections.add(new Detection("Docker", "DevOps", owner + "/" + repo, "Dockerfile", "Présence du fichier", "moyenne", at, ref));
        for (String path : List.of("docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")) {
            if (entries.containsKey(path)) detections.add(new Detection("Docker Compose", "DevOps", owner + "/" + repo, path, "Présence du fichier", "moyenne", at, ref));
        }
        var paths = entries.keySet().stream().map(v -> v.toLowerCase(Locale.ROOT)).toList();
        var checks = new ArrayList<Check>();
        String[] keys = {"readme", "tests", "license", "ci", "docs", "versions"};
        String[] labels = {"Présentation du projet", "Fichiers de tests repérés", "Licence", "Configuration CI", "Documentation", "Notes de versions"};
        boolean[] found = {
            paths.stream().anyMatch(p -> p.matches("(^|.*/)readme(?:\\.[^/]*)?")),
            paths.stream().anyMatch(p -> p.matches(".*(?:^|/)(?:test|tests|__tests__)/.*") || p.endsWith("_test.dart") || p.endsWith("test.java")),
            (!metadata.path("license").isNull() && !metadata.path("license").isMissingNode()) || paths.stream().anyMatch(p -> p.matches("(^|.*/)(license|copying)(?:\\.[^/]*)?")),
            paths.stream().anyMatch(p -> p.startsWith(".github/workflows/") || p.equals(".gitlab-ci.yml")),
            paths.stream().anyMatch(p -> p.startsWith("docs/") || p.startsWith("documentation/")),
            paths.stream().anyMatch(p -> p.startsWith("changelog") || p.startsWith("changes."))
        };
        for (int i = 0; i < keys.length; i++) checks.add(new Check(keys[i], labels[i], found[i] ? Boolean.TRUE : treeComplete ? Boolean.FALSE : null));
        Integer maturity = checks.stream().anyMatch(c -> c.present() == null) ? null :
            (int) Math.round(100.0 * checks.stream().filter(c -> Boolean.TRUE.equals(c.present())).count() / checks.size());
        warnings.add("Lecture statique de trois manifestes racine au maximum (100 Kio chacun). Les monorepos et dépendances indirectes peuvent être incomplets.");
        warnings.add("Maturité = critères présents / six critères × 100, uniquement quand tous sont vérifiables. La présence de tests ne garantit pas leur qualité. Aucun audit de sécurité n’est effectué.");
        return new RepositoryIntelligence(metadata.path("owner").path("login").asText(owner), metadata.path("name").asText(repo),
            metadata.path("html_url").asText("https://github.com/" + owner + "/" + repo), metadata.path("description").asText(""),
            metadata.path("language").asText(""), metadata.path("stargazers_count").asLong(), metadata.path("forks_count").asLong(),
            metadata.path("size").asLong(), metadata.path("created_at").asText(""), metadata.path("pushed_at").asText(""),
            metadata.path("license").path("spdx_id").asText(""), metadata.path("archived").asBoolean(), at, ref, complete, detections, checks, maturity, warnings);
    }
}
