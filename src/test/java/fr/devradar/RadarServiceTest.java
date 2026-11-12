package fr.devradar;

import fr.devradar.domain.model.Radar;
import fr.devradar.data.repository.GitHubClient;
import fr.devradar.data.repository.SnapshotRepository;
import fr.devradar.data.implementation.RadarServiceImpl;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RadarServiceTest {
    private final GitHubClient github = mock(GitHubClient.class);
    private final SnapshotRepository store = mock(SnapshotRepository.class);
    private final RadarServiceImpl service = new RadarServiceImpl(github, store);
    private final ObjectMapper json = new ObjectMapper();
    @Test void calculeUnSignalTransparentEtReutiliseLeCache() throws Exception {
        when(github.user("alice")).thenReturn(json.readTree("{\"login\":\"alice\",\"public_repos\":3}"));
        when(github.repositories("alice")).thenReturn(List.of(
            json.readTree("{\"name\":\"api\",\"language\":\"Java\",\"stargazers_count\":9,\"description\":\"API\"}"),
            json.readTree("{\"name\":\"fork\",\"fork\":true,\"stargazers_count\":999}"),
            json.readTree("{\"name\":\"app\",\"language\":\"Dart\",\"stargazers_count\":0}")));
        when(github.events("alice")).thenReturn(List.of(
            json.readTree("{\"created_at\":\"" + Instant.now() + "\"}"),
            json.readTree("{\"created_at\":\"2020-01-01T00:00:00Z\"}")));
        Radar radar = service.find("Alice");
        assertThat(radar.stars()).isEqualTo(9);
        assertThat(radar.observedEvents30Days()).isEqualTo(1);
        assertThat(radar.streakDays()).isEqualTo(1);
        assertThat(radar.score()).isEqualTo(23); // 4 + 5 + 1 + 13
        assertThat(radar.languages()).allSatisfy(l -> assertThat(l.pct()).isEqualTo(50));
        assertThat(service.find("ALICE")).isSameAs(radar);
        verify(github, times(1)).user("alice");
        verify(store).save(radar);
    }
    @Test void refuseUnPseudoInvalideAvantToutAppelReseau() {
        assertThatThrownBy(() -> service.find("../secret")).hasMessageContaining("400");
        verifyNoInteractions(github, store);
    }
    @Test void unProfilVidePossedeUnScoreNul() throws Exception {
        when(github.user("empty")).thenReturn(json.readTree("{\"login\":\"empty\"}"));
        when(github.repositories("empty")).thenReturn(List.of());
        when(github.events("empty")).thenReturn(List.of());
        assertThat(service.find("empty").score()).isZero();
    }
    @Test void conserveLeProfilQuandLesEvenementsSontIndisponibles() throws Exception {
        when(github.user("alice")).thenReturn(json.readTree("{\"login\":\"alice\",\"bio\":\"Développeuse\"}"));
        when(github.repositories("alice")).thenReturn(List.of());
        when(github.events("alice")).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        var radar = service.find("alice");
        assertThat(radar.coverage().eventsAvailable()).isFalse();
        assertThat(radar.details().bio()).isEqualTo("Développeuse");
        verifyNoInteractions(store);
    }
    @Test void unePanneSqlNeBloquePasLaConsultation() throws Exception {
        when(github.user("alice")).thenReturn(json.readTree("{\"login\":\"alice\"}"));
        when(github.repositories("alice")).thenReturn(List.of());
        when(github.events("alice")).thenReturn(List.of());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("indisponible")).when(store).save(any());
        assertThat(service.find("alice").username()).isEqualTo("alice");
    }
}
