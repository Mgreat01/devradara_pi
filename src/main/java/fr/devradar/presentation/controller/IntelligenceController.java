package fr.devradar.presentation.controller;

import fr.devradar.domain.service.*;
import fr.devradar.domain.model.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class IntelligenceController {
    private final RadarService profiles;
    private final IntelligenceService intelligence;
    private final EvolutionService evolution;
    public IntelligenceController(RadarService profiles, IntelligenceService intelligence, EvolutionService evolution) {
        this.profiles = profiles; this.intelligence = intelligence; this.evolution = evolution;
    }
    public record Indicator(String key, String label, Double value, String formula, String limitation) {}
    public record ProfileIntelligence(Radar profile, List<RepositoryIntelligence> analyzedRepositories, List<Indicator> indicators, String scope) {}
    @GetMapping("/radar/{username}/intelligence")
    public ProfileIntelligence profile(@PathVariable String username) {
        var radar = profiles.find(username);
        var projects = intelligence.known(username);
        long technologies = projects.stream().flatMap(p -> p.technologies().stream()).map(t -> t.name()).distinct().count();
        var maturity = projects.stream().filter(p -> p.maturity() != null).mapToInt(p -> p.maturity()).average();
        return new ProfileIntelligence(radar, projects, List.of(
            new Indicator("visibility", "Visibilité publique", radar.coverage().complete() ? (double) radar.score() : null, "Score historique sur 100, formule inchangée", "Ne mesure pas les compétences."),
            new Indicator("diversity", "Diversité observée", projects.isEmpty() ? null : Math.min(100.0, technologies * 20.0), "min(100, nombre de technologies distinctes détectées × 20)", "Dépend des projets analysés ; aucune déduction à partir du langage seul."),
            new Indicator("activity", "Activité publique", radar.coverage().eventsAvailable() ? Math.min(100.0, radar.observedEvents30Days()) : null, "min(100, événements publics observés sur 30 jours)", "Les événements ne sont pas des commits ; collecte plafonnée."),
            new Indicator("portfolio", "Projets présentés", radar.coverage().repositoriesAvailable() ? Math.min(100.0, radar.topRepos().size() / 6.0 * 100) : null, "Nombre de projets dans la sélection / 6 × 100", "Mesure la taille de la sélection, pas la qualité des projets."),
            new Indicator("maturity", "Signaux de maturité", maturity.isPresent() ? maturity.getAsDouble() : null, "Moyenne des maturités calculables des projets analysés", "Échantillon limité ; aucun audit qualité ou sécurité.")),
            "Indicateurs v1, non additionnés. Les dimensions peuvent se recouper. Technologies issues uniquement des analyses demandées et encore en cache (24 h au plus).");
    }
    @GetMapping("/repositories/{owner}/{repo}/intelligence")
    public RepositoryIntelligence repository(@PathVariable String owner, @PathVariable String repo) { return intelligence.repository(owner, repo); }
    @GetMapping("/radar/{username}/evolution")
    public Evolution history(@PathVariable String username, @RequestParam(defaultValue="30") int days) {
        validate(username);
        if (!Set.of(0, 7, 30, 90, 180, 365).contains(days)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Période invalide.");
        try { return evolution.history(username, days); }
        catch (DataAccessException ex) { return new Evolution(username, null, 365, false, List.of(), "L’historique est temporairement indisponible."); }
    }
    public record Wrapped(int year, int observations, Evolution.Point first, Evolution.Point last, String limitation) {}
    @GetMapping("/radar/{username}/wrapped")
    public Wrapped wrapped(@PathVariable String username, @RequestParam int year) {
        if (year < 2008 || year > Year.now(ZoneOffset.UTC).getValue()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Année invalide.");
        var history = history(username, 0);
        if (!history.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, history.limitation());
        var points = history.points().stream().filter(p -> p.observedAt().atZone(ZoneOffset.UTC).getYear() == year).toList();
        return new Wrapped(year, points.size(), points.isEmpty() ? null : points.getFirst(), points.isEmpty() ? null : points.getLast(),
            "Bilan des observations conservées, pas une reconstitution de l’année GitHub. Aucune donnée avant la première collecte ; rétention 365 jours.");
    }
    private void validate(String username) {
        if (!username.matches("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,37}[a-zA-Z0-9])?")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pseudo invalide.");
    }
}
