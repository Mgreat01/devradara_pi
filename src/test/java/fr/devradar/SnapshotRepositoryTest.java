package fr.devradar;
import fr.devradar.data.repository.SnapshotRepository;
import fr.devradar.domain.model.Radar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@JdbcTest
@Import(SnapshotRepository.class)
class SnapshotRepositoryTest {
    @Autowired SnapshotRepository repository;
    @Autowired JdbcTemplate jdbc;
    private Radar radar(String name, Instant at) {
        return new Radar(name, name, "", "https://github.com/" + name, 42, new Radar.ScoreBreakdown(10,10,10,12),
            12, 4, 7, 1, List.of(new Radar.Language("Java", 100)), List.of(), at, List.of(),
            new Radar.Profile("", "", "", 0, 0, ""), new Radar.Coverage(true, true, false, false));
    }
    @Test void conserveLesDonneesExistantesEtUnSeulPointParJour() {
        var profile = radar("observed", Instant.now());
        repository.save(profile); repository.observe(profile, List.of(1L, 2L)); repository.observe(radar("OBSERVED", profile.cachedAt()), List.of(1L, 2L, 3L));
        assertThat(jdbc.queryForObject("select count(*) from radar_snapshot where username = 'observed'", Integer.class)).isEqualTo(1);
        var history = repository.history("OBSERVED", 30);
        assertThat(history.points()).hasSize(1);
        assertThat(history.points().getFirst().newlyObservedRepositories()).isNull();
    }
    @Test void neReconstituePasLePasseEtCompteLesIdentifiantsNouvellementObserves() {
        repository.observe(radar("observed", Instant.now().minus(Duration.ofDays(2))), List.of(1L));
        repository.observe(radar("observed", Instant.now()), List.of(1L, 2L));
        var history = repository.history("observed", 7);
        assertThat(history.points()).hasSize(2);
        assertThat(history.points().getLast().newlyObservedRepositories()).isEqualTo(1);
        assertThat(repository.history("missing", 365).points()).isEmpty();
    }
    @Test void respecteLePlafondGlobalSansSupprimerLesAnciennesLignes() {
        jdbc.batchUpdate("insert into profile_observation(username, observation_day, observed_at, score, stars, repositories, events, languages_json, repository_ids_json, truncated) values (?, CURRENT_DATE, CURRENT_TIMESTAMP, 0, 0, 0, 0, '[]', '[]', false)",
            java.util.stream.IntStream.range(0, 5000).mapToObj(i -> new Object[]{"u" + i}).toList());
        repository.observe(radar("over-limit", Instant.now()), List.of());
        assertThat(jdbc.queryForObject("select count(*) from profile_observation", Integer.class)).isEqualTo(5000);
        assertThat(repository.history("over-limit", 7).points()).isEmpty();
    }
}
