package fr.devradar;

import fr.devradar.domain.model.Radar;
import fr.devradar.data.repository.GitHubClient;
import fr.devradar.data.repository.SnapshotRepository;
import fr.devradar.data.implementation.RadarServiceImpl;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RadarApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean GitHubClient github;
    @Test void exposeLaRechercheParNomSansCasserLesAnciensFiltres() throws Exception {
        when(github.search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq("best_match")))
            .thenReturn(json.readTree("{\"items\":[{\"login\":\"Mgreat01\"}],\"total_count\":1}"));
        mvc.perform(get("/api/v1/discovery").param("q", "Mgreat01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].username").value("Mgreat01"));
        mvc.perform(get("/api/v1/discovery").param("q", "user:root"))
            .andExpect(status().isBadRequest());
    }
    @Test void exposeCarteScoreEtComparaison() throws Exception {
        for (String username : List.of("alice", "bob")) {
            when(github.user(username)).thenReturn(json.readTree("{\"login\":\"" + username + "\"}"));
            when(github.repositories(username)).thenReturn(List.of());
            when(github.events(username)).thenReturn(List.of());
        }
        mvc.perform(get("/api/v1/radar/alice")).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice")).andExpect(jsonPath("$.score").value(0));
        mvc.perform(get("/api/v1/radar/alice/score")).andExpect(status().isOk())
                .andExpect(jsonPath("$.breakdown.activity").value(0));
        mvc.perform(get("/api/v1/radar/compare").param("a", "alice").param("b", "bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.b.username").value("bob"));
    }
    @Test void demarreAvecMigrationEtExposeLaSante() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
    @Test void retourneUneErreurStructureePourUnPseudoInvalide() throws Exception {
        mvc.perform(get("/api/v1/radar/-invalide")).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
    @Test void autoriseUniquementLesOriginesConfigurees() throws Exception {
        mvc.perform(get("/api/v1/radar/-invalide").header("Origin", "https://inconnu.test"))
                .andExpect(status().isForbidden());
    }
    @Test void limiteLesRequetesParAdresseAvecUnDelaiDeReprise() throws Exception {
        for (int request = 0; request < 20; request++) {
            mvc.perform(get("/api/v1/radar/-invalide").with(req -> {
                req.setRemoteAddr("192.0.2.42");
                return req;
            })).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/radar/-invalide").with(req -> {
            req.setRemoteAddr("192.0.2.42");
            return req;
        })).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.status").value(429));
    }
    @Test void conserveUneObservationQuotidienneEtPreserveLesRoutes() throws Exception {
        when(github.user("history")).thenReturn(json.readTree("{\"login\":\"history\"}"));
        when(github.repositories("history")).thenReturn(List.of());
        when(github.events("history")).thenReturn(List.of());
        mvc.perform(get("/api/v1/radar/history")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/radar/history/evolution").param("days", "30"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.points.length()").value(1));
        mvc.perform(get("/api/v1/radar/history/intelligence")).andExpect(status().isOk())
            .andExpect(jsonPath("$.indicators.length()").value(5));
        mvc.perform(get("/api/v1/radar/history/evolution").param("days", "9999")).andExpect(status().isBadRequest());
    }
}
