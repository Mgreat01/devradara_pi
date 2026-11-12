package fr.devradar;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.devradar.data.implementation.DiscoveryServiceImpl;
import fr.devradar.data.repository.GitHubClient;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveryTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void chercheUnPseudoAvecPertinenceEtReutiliseLeCache() throws Exception {
        var github = mock(GitHubClient.class);
        var service = new DiscoveryServiceImpl(github);
        String query = "type:user \"Mgreat01\" in:login,name";
        when(github.search(query, 1, "best_match")).thenReturn(json.readTree("{\"items\":[{\"login\":\"Mgreat01\"}],\"total_count\":1}"));
        assertThat(service.search("@Mgreat01", "", "", 0, 1, "best_match").items()).extracting(u -> u.username()).containsExactly("Mgreat01");
        service.search("https://github.com/Mgreat01/", "", "", 0, 1, "best_match");
        verify(github, times(1)).search(query, 1, "best_match");
        verifyNoMoreInteractions(github);
    }
    @Test void conserveLesFiltresSurLaPageDemandeeEtAccepteLesAccents() throws Exception {
        var github = mock(GitHubClient.class);
        String query = "type:user repos:>=3 \"Éphraïm\" \"Monga\" in:login,name language:\"C++\" location:\"Paris, France\"";
        when(github.search(query, 2, "followers")).thenReturn(json.readTree("{\"items\":[],\"total_count\":0}"));
        var result = new DiscoveryServiceImpl(github).search(" Éphraïm  Monga ", "C++", "Paris, France", 3, 2, "followers");
        assertThat(result.page()).isEqualTo(2);
        verify(github).search(query, 2, "followers");
    }
    @Test void refuseLesOperateursEtLesLiensQuiNeSontPasDesProfils() {
        var github = mock(GitHubClient.class);
        var service = new DiscoveryServiceImpl(github);
        for (String term : new String[]{"alice user:root", "alice OR bob", "https://evil.test/alice", "https://github.com/alice/repo", "x".repeat(81)}) {
            assertThatThrownBy(() -> service.search(term, "", "", 0, 1, "best_match")).hasMessageContaining("400");
        }
        verifyNoInteractions(github);
    }
    @Test void uneErreurOuUneReponseInvalideNeDevientPasUnResultatVideEnCache() throws Exception {
        var github = mock(GitHubClient.class);
        when(github.search(anyString(), eq(1), eq("best_match"))).thenReturn(json.readTree("{}"),json.readTree("{\"items\":[],\"total_count\":0}"));
        var service = new DiscoveryServiceImpl(github);
        assertThatThrownBy(() -> service.search("alice", "", "", 0, 1, "best_match")).hasMessageContaining("503");
        assertThat(service.search("alice", "", "", 0, 1, "best_match").items()).isEmpty();
        verify(github, times(2)).search(anyString(), eq(1), eq("best_match"));
    }
}
