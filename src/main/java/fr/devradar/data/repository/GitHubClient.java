package fr.devradar.data.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Component
public class GitHubClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String base, token;
    private final ThreadLocal<Budget> budget = new ThreadLocal<>();
    private final Map<String, Window> windows = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private static final class Budget { long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos(); int calls; }
    private static final class Window { long minute = System.currentTimeMillis(), hour = minute; int perMinute, perHour; }
    public GitHubClient(@Value("${devradar.github-url}") String base,
                        @Value("${devradar.github-token}") String token) {
        this.base = base.replaceAll("/$", ""); this.token = token;
    }
    public void beginBudget() { budget.set(new Budget()); }
    public void endBudget() { budget.remove(); }
    public <T> T withinBudget(Supplier<T> action) {
        if (budget.get() != null) return action.get();
        beginBudget();
        try { return action.get(); } finally { endBudget(); }
    }
    public JsonNode user(String username) { return get("/users/" + username); }
    public List<JsonNode> repositories(String username) {
        return pages("/users/" + username + "/repos?type=owner&sort=updated&per_page=100", 5);
    }
    public List<JsonNode> events(String username) {
        return pages("/users/" + username + "/events/public?per_page=100", 3);
    }
    public JsonNode repository(String owner, String repo) { return get("/repos/" + owner + "/" + repo); }
    public JsonNode tree(String owner, String repo, String ref) {
        return get("/repos/" + owner + "/" + repo + "/git/trees/" + encode(ref) + "?recursive=1");
    }
    public JsonNode blob(String owner, String repo, String sha) {
        if (!sha.matches("[a-fA-F0-9]{40,64}")) throw new IllegalArgumentException("SHA invalide");
        return get("/repos/" + owner + "/" + repo + "/git/blobs/" + sha);
    }
    public JsonNode search(String query, int page, String sort) {
        return get("/search/users?q=" + encode(query) + "&per_page=10&page=" + page + (sort.equals("best_match") ? "" : "&sort=" + sort));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private List<JsonNode> pages(String path, int maximum) {
        return withinBudget(() -> {
            var result = new ArrayList<JsonNode>();
            for (int page = 1; page <= maximum; page++) {
                var nodes = get(path + "&page=" + page);
                if (!nodes.isArray()) throw unavailable("RÃ©ponse GitHub inattendue.", 0);
                nodes.forEach(result::add);
                if (nodes.size() < 100) break;
            }
            return result;
        });
    }
    private synchronized void reserve(String family) {
        long now = System.currentTimeMillis();
        long pause = cooldowns.getOrDefault(family, 0L);
        if (pause > now) throw unavailable("GitHub est momentanÃ©ment indisponible.", (pause - now + 999) / 1000);
        Window w = windows.computeIfAbsent(family, key -> new Window());
        if (now - w.minute >= 60_000) { w.minute = now; w.perMinute = 0; }
        if (now - w.hour >= 3_600_000) { w.hour = now; w.perHour = 0; }
        int minuteLimit = family.equals("search") ? 5 : 60;
        int hourLimit = family.equals("search") || token.isBlank() ? 50 : 3500;
        if (w.perMinute >= minuteLimit || w.perHour >= hourLimit)
            throw unavailable("Le service a atteint sa limite de consultation. RÃ©essayez plus tard.",
                w.perHour >= hourLimit ? (3_600_000 - now + w.hour) / 1000 + 1 : (60_000 - now + w.minute) / 1000 + 1);
        w.perMinute++; w.perHour++;
    }
    private JsonNode get(String path) {
        return withinBudget(() -> {
            Budget b = budget.get();
            long remaining = b.deadline - System.nanoTime();
            if (++b.calls > 18 || remaining < Duration.ofMillis(100).toNanos())
                throw unavailable("La collecte a atteint sa limite. Les donnÃ©es disponibles sont conservÃ©es.", 30);
            String family = path.startsWith("/search/") ? "search" : "core";
            reserve(family);
            var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(8).toNanos())))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "DevRadar");
            if (!token.isBlank() && !family.equals("search")) request.header("Authorization", "Bearer " + token);
            try {
                var response = client.send(request.GET().build(), info -> new LimitedBody());
                int status = response.statusCode();
                if (status == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ressource GitHub introuvable");
                if (status == 403 || status == 429) {
                    long seconds = response.headers().firstValueAsLong("Retry-After").orElse(60);
                    if (response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0"))
                        seconds = Math.max(seconds, response.headers().firstValueAsLong("X-RateLimit-Reset").orElse(0) - Instant.now().getEpochSecond());
                    seconds = Math.max(1, Math.min(3600, seconds));
                    synchronized (this) { cooldowns.put(family, System.currentTimeMillis() + seconds * 1000); }
                    throw unavailable("GitHub est momentanÃ©ment indisponible. RÃ©essayez plus tard.", seconds);
                }
                if (status == 422) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cette recherche nâ€™est pas acceptÃ©e par GitHub.");
                if (status < 200 || status >= 300) throw unavailable("GitHub est momentanÃ©ment indisponible.", 0);
                var json = mapper.readTree(response.body());
                if (json == null) throw unavailable("RÃ©ponse GitHub vide.", 0);
                return json;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw unavailable("La consultation a Ã©tÃ© interrompue.", 0);
            } catch (java.io.IOException | IllegalArgumentException ex) {
                throw unavailable("Impossible de joindre GitHub pour le moment.", 0);
            }
        });
    }
    private static ResponseStatusException unavailable(String message, long retry) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message) {
            @Override public org.springframework.http.HttpHeaders getHeaders() {
                var headers = new org.springframework.http.HttpHeaders();
                if (retry > 0) headers.set("Retry-After", Long.toString(retry));
                return headers;
            }
        };
    }
    /** Annule la rÃ©ponse avant de conserver plus de 1 Mio, y compris pour les arbres volumineux. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 1_048_576) {
                    subscription.cancel(); body.completeExceptionally(new java.io.IOException("RÃ©ponse trop volumineuse")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
