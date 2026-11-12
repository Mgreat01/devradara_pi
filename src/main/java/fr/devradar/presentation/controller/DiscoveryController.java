package fr.devradar.presentation.controller;
import fr.devradar.domain.service.DiscoveryService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {
    private final DiscoveryService discovery;
    public DiscoveryController(DiscoveryService discovery) { this.discovery = discovery; }
    @GetMapping("/analyzed")
    public DiscoveryService.Result analyzed(@RequestParam(defaultValue="") String technology, @RequestParam(defaultValue="") String domain,
        @RequestParam(defaultValue="0") long minimumStars, @RequestParam(defaultValue="0") int activeDays, @RequestParam(defaultValue="1") int page) {
        return discovery.analyzed(technology, domain, minimumStars, activeDays, page);
    }
    @GetMapping
    public DiscoveryService.Result search(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String language, @RequestParam(defaultValue="") String location,
        @RequestParam(defaultValue="0") int minimumRepos, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="best_match") String sort) {
        return discovery.search(q, language, location, minimumRepos, page, sort);
    }
}
