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
}
