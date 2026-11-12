package fr.devradar.domain.service;
import fr.devradar.domain.model.RepositoryIntelligence;
import java.util.List;
public interface IntelligenceService {
    RepositoryIntelligence repository(String owner, String repo);
    List<RepositoryIntelligence> known(String username);
    List<RepositoryIntelligence> observed();
}
