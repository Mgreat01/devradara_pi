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
    private final Cache<String, Result> cache = Caffeine.newBuilder().maximumSize(100).expireAfterWrite(Duration.ofMinutes(10)).build();
    public DiscoveryServiceImpl(GitHubClient github) { this.github = github; }
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
