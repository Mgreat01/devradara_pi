package fr.devradar;
import fr.devradar.data.implementation.*;
import fr.devradar.data.repository.GitHubClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class IntelligenceTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void detecteUneDependanceMaisPasUnLangageNiUneMentionDansLaDescription() {
        var detected = TechnologyDetector.detect("package.json", "{\"dependencies\":{\"react\":\"18\"},\"description\":\"Vue\"}", "alice/web", Instant.now(), "sha");
        assertThat(detected).extracting(d -> d.name()).containsExactly("React");
        assertThat(detected.getFirst().file()).isEqualTo("package.json");
        assertThat(TechnologyDetector.detect("package.json", "{\"name\":\"react\",\"description\":\"React\"}", "a/b", Instant.now(), "sha")).isEmpty();
    }
    @Test void gardeLesDonneesQuandLaListeDesFichiersEstIndisponible() throws Exception {
        var client = mock(GitHubClient.class);
        when(client.repository("alice", "api")).thenReturn(json.readTree("{\"name\":\"api\",\"stargazers_count\":4}"));
        when(client.tree(anyString(), anyString(), anyString())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        var service = new IntelligenceServiceImpl(client);
        var result = service.repository("alice", "api");
        assertThat(result.stars()).isEqualTo(4);
        assertThat(result.complete()).isFalse();
        assertThat(result.maturity()).isNull();
        assertThat(result.checks()).allMatch(c -> c.present() == null);
        assertThat(service.repository("alice", "api")).isSameAs(result);
        verify(client, times(1)).repository("alice", "api");
    }
    @Test void limiteLaDecouverteEtRefuseLesQualificateursInjectes() {
        var client = mock(GitHubClient.class);
        var discovery = new DiscoveryServiceImpl(client);
        assertThatThrownBy(() -> discovery.search("Java user:root", "", 0, 1, "followers")).hasMessageContaining("400");
        assertThatThrownBy(() -> discovery.search("Java", "", 0, 6, "followers")).hasMessageContaining("400");
        verifyNoInteractions(client);
    }
}
