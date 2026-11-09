package fr.devradar.presentation.controller;

import fr.devradar.domain.model.Radar;
import fr.devradar.domain.service.RadarService;
import org.springframework.web.bind.annotation.*;
import fr.devradar.presentation.dto.RadarScoreResponse;
import fr.devradar.presentation.dto.RadarComparisonResponse;

@RestController
@RequestMapping("/api/v1/radar")
public class RadarController {
    private final RadarService service;
    public RadarController(RadarService service) { this.service = service; }
    @GetMapping("/{username}")
    public Radar profile(@PathVariable String username) { return service.find(username); }
    @GetMapping("/{username}/score")
    public RadarScoreResponse score(@PathVariable String username) {
        return RadarScoreResponse.from(service.find(username));
    }
    @GetMapping("/compare")
    public RadarComparisonResponse compare(@RequestParam String a, @RequestParam String b) {
        return new RadarComparisonResponse(service.find(a), service.find(b));
    }
}
