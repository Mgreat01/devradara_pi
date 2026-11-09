package fr.devradar;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class GitHubClient {
    private final RestClient client;
    public GitHubClient(@Value("${devradar.github-url}") String base,
                        @Value("${devradar.github-token}") String token) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        var builder = RestClient.builder().baseUrl(base).requestFactory(factory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "DevRadar");
        if (!token.isBlank()) builder.defaultHeader("Authorization", "Bearer " + token);
        client = builder.build();
    }
    public JsonNode user(String username) { return get("/users/" + username); }
    public List<JsonNode> repositories(String username) {
        return pages("/users/" + username + "/repos?type=owner&sort=updated&per_page=100", 5);
    }
    public List<JsonNode> events(String username) {
        return pages("/users/" + username + "/events/public?per_page=100", 3);
    }
    private List<JsonNode> pages(String path, int maximum) {
        var result = new ArrayList<JsonNode>();
        for (int page = 1; page <= maximum; page++) {
            JsonNode nodes = get(path + "&page=" + page);
            nodes.forEach(result::add);
            if (nodes.size() < 100) break;
        }
        return result;
    }
    private JsonNode get(String path) {
        try {
            return client.get().uri(path).retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profil GitHub introuvable");
                })
                .onStatus(s -> s.value() == 403 || s.value() == 429, (req, res) -> {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Quota GitHub indisponible. Réessayez plus tard.");
                }).body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub est temporairement indisponible");
        }
    }
}
