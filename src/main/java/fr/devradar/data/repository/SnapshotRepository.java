package fr.devradar.data.repository;

import fr.devradar.domain.model.Radar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SnapshotRepository {
    private final JdbcTemplate jdbc;
    public SnapshotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public void save(Radar radar) {
        jdbc.update("delete from radar_snapshot where username = ?", radar.username());
        jdbc.update("insert into radar_snapshot(username, score, observed_at) values (?, ?, ?)",
                radar.username(), radar.score(), java.sql.Timestamp.from(radar.cachedAt()));
    }
}
