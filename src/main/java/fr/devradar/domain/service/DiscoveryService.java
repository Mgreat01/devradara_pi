package fr.devradar.domain.service;
import java.util.List;
public interface DiscoveryService {
    record User(String username, String avatarUrl, String profileUrl) {}
    record Result(List<User> items, int page, long totalCount, boolean incomplete, int maximumPages, String scope) {}
    Result search(String language, String location, int minimumRepos, int page, String sort);
}
