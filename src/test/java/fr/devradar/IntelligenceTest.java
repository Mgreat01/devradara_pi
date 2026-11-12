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
    @Test void detecteFlutterDansLesDependancesEtSpringDansGradle() {
        assertThat(TechnologyDetector.detect("pubspec.yaml", "name: flutter\ndependencies:\n  http: ^1.0.0\n", "a/b", Instant.now(), "sha")).isEmpty();
        assertThat(TechnologyDetector.detect("pubspec.yaml", "name: app\ndependencies:\n  flutter:\n    sdk: flutter\n", "a/b", Instant.now(), "sha")).extracting(d -> d.name()).contains("Flutter");
        assertThat(TechnologyDetector.detect("build.gradle", "plugins { id 'org.springframework.boot' version '3.5' }", "a/b", Instant.now(), "sha")).extracting(d -> d.name()).contains("Spring Boot");
        assertThatThrownBy(() -> TechnologyDetector.detect("pom.xml", "<!DOCTYPE x SYSTEM 'file:///inexistant'><project/>", "a/b", Instant.now(), "sha")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void respecteLaTailleDesManifestesEtCalculeLesCriteresVerifiables() throws Exception {
        var client = mock(GitHubClient.class);
        when(client.repository("alice", "web")).thenReturn(json.readTree("{\"name\":\"web\"}"));
        when(client.tree(anyString(), anyString(), anyString())).thenReturn(json.readTree("{\"sha\":\"ref\",\"truncated\":false,\"tree\":[{\"path\":\"package.json\",\"type\":\"blob\",\"size\":200000,\"sha\":\"big\"},{\"path\":\"README.md\",\"type\":\"blob\"},{\"path\":\"src/test/test.java\",\"type\":\"blob\"}]}"));
        var result = new IntelligenceServiceImpl(client).repository("alice", "web");
        assertThat(result.complete()).isFalse();
        assertThat(result.maturity()).isEqualTo(33);
        assertThat(result.technologies()).isEmpty();
        verify(client, never()).blob(anyString(), anyString(), anyString());
    }
    @Test void filtreLesAnalysesConnuesSansNouvelleCollecteGitHub() throws Exception {
        var github = mock(GitHubClient.class);
        var intelligence = mock(fr.devradar.domain.service.IntelligenceService.class);
        var project = new fr.devradar.domain.model.RepositoryIntelligence("alice", "app", "https://github.com/alice/app", "", "Dart", 12, 0, 1, "", Instant.now().toString(), "MIT", false, Instant.now(), "sha", true,
            java.util.List.of(new fr.devradar.domain.model.RepositoryIntelligence.Detection("Flutter", "Mobile", "alice/app", "pubspec.yaml", "dependency", "élevée", Instant.now(), "sha")), java.util.List.of(), 50, java.util.List.of());
        when(intelligence.observed()).thenReturn(java.util.List.of(project));
        var discovery = new DiscoveryServiceImpl(github, intelligence);
        assertThat(discovery.analyzed("Flutter", "Mobile", 10, 30, 1).items()).extracting(u -> u.username()).containsExactly("alice");
        assertThat(discovery.analyzed("Spring Boot", "", 0, 0, 1).items()).isEmpty();
        verifyNoInteractions(github);
    }
}
