package fr.devradar.domain.model;
import java.time.Instant;
import java.util.List;
public record Evolution(String username, Instant firstObservedAt, int retentionDays, boolean available,
                        List<Point> points, String limitation) {
    public record Point(Instant observedAt, int score, long stars, int repositories, int events,
                        List<Radar.Language> languages, Integer newlyObservedRepositories, boolean truncated) {}
}
