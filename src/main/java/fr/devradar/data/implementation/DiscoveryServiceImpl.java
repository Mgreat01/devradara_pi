package fr.devradar.data.implementation;
import fr.devradar.domain.service.DiscoveryService;
import fr.devradar.data.repository.GitHubClient;
import com.github.benmanes.caffeine.cache.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;
@Service
public class DiscoveryServiceImpl implements DiscoveryService {
    private final GitHubClient github;
    private final fr.devradar.domain.service.IntelligenceService intelligence;
    private final Cache<String, Result> cache = Caffeine.newBuilder().maximumSize(100).expireAfterWrite(Duration.ofMinutes(10)).build();
    @org.springframework.beans.factory.annotation.Autowired
    public DiscoveryServiceImpl(GitHubClient github, fr.devradar.domain.service.IntelligenceService intelligence) { this.github = github; this.intelligence = intelligence; }
    public DiscoveryServiceImpl(GitHubClient github) { this(github, null); }
    public Result analyzed(String technology, String domain, long minimumStars, int activeDays, int page) {
        if (technology.length() > 60 || domain.length() > 60 || minimumStars < 0 || minimumStars > 1_000_000_000 || activeDays < 0 || activeDays > 365 || page < 1 || page > 5)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtres invalides.");
        var projects = intelligence == null ? List.<fr.devradar.domain.model.RepositoryIntelligence>of() : intelligence.observed();
        String tech = technology.trim().toLowerCase(Locale.ROOT), area = domain.trim().toLowerCase(Locale.ROOT);
        var users = projects.stream().filter(p -> p.stars() >= minimumStars)
            .filter(p -> activeDays == 0 || recent(p.pushedAt(), activeDays))
            .filter(p -> p.technologies().stream().anyMatch(t -> t.name().toLowerCase(Locale.ROOT).contains(tech) && t.domain().toLowerCase(Locale.ROOT).contains(area)) || (tech.isBlank() && area.isBlank()))
            .map(p -> p.owner().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        return new Result(users.stream().skip((long) (page - 1) * 10).limit(10).map(name -> new User(name, "", "https://github.com/" + name)).toList(), page, users.size(), false, 5,
            "Filtres appliqués aux projets analysés encore en mémoire (24 h maximum, cache vidé au redémarrage). Étoiles et activité concernent un projet correspondant, pas tout le profil. Aucun appel GitHub supplémentaire pour ce filtrage.");
    }
    private boolean recent(String pushedAt, int days) {
        try { return !java.time.Instant.parse(pushedAt).isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(days))); }
        catch (java.time.DateTimeException ex) { return false; }
    }
    public Result search(String language, String location, int minimumRepos, int page, String sort) {
        language = language.trim(); location = location.trim();
        if (!language.matches("[\\p{L}\\p{N} ._+#/-]{0,40}") || !location.matches("[\\p{L}\\p{N} ._'-]{0,60}") ||
            minimumRepos < 0 || minimumRepos > 10000 || page < 1 || page > 5 || !Set.of("followers", "repositories", "joined").contains(sort))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtres de recherche invalides.");
        String query = "type:user repos:>=" + minimumRepos + (language.isBlank() ? "" : " language:\"" + language + "\"") +
            (location.isBlank() ? "" : " location:\"" + location + "\"");
        return cache.get(query + "|" + page + "|" + sort, key -> {
            var response = github.search(query, page, sort);
            var items = new ArrayList<User>();
            for (var user : response.path("items")) {
                if (items.size() >= 10) break;
                items.add(new User(user.path("login").asText(), user.path("avatar_url").asText(""), user.path("html_url").asText("")));
            }
            return new Result(items, page, response.path("total_count").asLong(), response.path("incomplete_results").asBoolean(), 5,
                "Recherche GitHub limitée à 50 résultats, sans analyse automatique des profils. Les filtres décrivent les données publiques indexées par GitHub ; aucune spécialisation professionnelle n’est garantie.");
        });
    }
}
