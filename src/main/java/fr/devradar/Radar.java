package fr.devradar;

import java.time.Instant;
import java.util.List;

public record Radar(String username, String name, String avatarUrl, String profileUrl,
                    int score, ScoreBreakdown scoreBreakdown, long stars, int publicRepos,
                    int observedEvents30Days, int streakDays, List<Language> languages,
                    List<Repo> topRepos, Instant cachedAt, List<String> limitations) {
    public record ScoreBreakdown(int repositories, int community, int activity, int documentation) {}
    public record Language(String name, double pct) {}
    public record Repo(String name, String url, String description, String language, long stars) {}
}
