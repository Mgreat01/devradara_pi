package fr.devradar;

import fr.devradar.domain.model.Radar;
import fr.devradar.data.repository.GitHubClient;
import fr.devradar.data.repository.SnapshotRepository;
import fr.devradar.data.implementation.RadarServiceImpl;


import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class GitHubClientTest {
    @Test void traduitLesErreursGitHubEtPagine() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/users/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            int status = path.endsWith("/missing") ? 404 : path.endsWith("/quota") ? 403 : 200;
            String body = path.endsWith("/repos") ? "[]" : "{\"login\":\"alice\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new GitHubClient("http://127.0.0.1:" + server.getAddress().getPort(), "");
            assertThat(client.user("alice").path("login").asText()).isEqualTo("alice");
            assertThat(client.repositories("alice")).isEmpty();
            assertThatThrownBy(() -> client.user("missing")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            assertThatThrownBy(() -> client.user("quota")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503")
                .satisfies(error -> assertThat(((ResponseStatusException) error).getHeaders().getFirst("Retry-After")).isEqualTo("60"));
            // Le délai est partagé : aucune nouvelle requête GitHub ne doit partir pendant la pause.
            assertThatThrownBy(() -> client.user("alice")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        } finally { server.stop(0); }
    }
    @Test void arreteLaCollecteAvantUnDixNeuviemeAppel() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/users/", exchange -> {
            calls.incrementAndGet(); byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var client = new GitHubClient("http://127.0.0.1:" + server.getAddress().getPort(), "");
            client.withinBudget(() -> {
                for (int i = 0; i < 18; i++) client.user("alice");
                assertThatThrownBy(() -> client.user("alice")).isInstanceOf(ResponseStatusException.class);
                return null;
            });
            assertThat(calls.get()).isEqualTo(18);
        } finally { server.stop(0); }
    }
}
