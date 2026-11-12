package fr.devradar.domain.model;
import java.time.Instant;
import java.util.List;
public record RepositoryIntelligence(String owner, String name, String url, String description, String language,
    long stars, long forks, long sizeKb, String createdAt, String pushedAt, String license,
    boolean archived, Instant analyzedAt, String sourceRef, boolean complete,
    List<Detection> technologies, List<Check> checks, Integer maturity, List<String> warnings) {
    public record Detection(String name, String domain, String repository, String file, String method,
                            String confidence, Instant analyzedAt, String sourceRef) {}
    public record Check(String name, String label, Boolean present) {}
}
