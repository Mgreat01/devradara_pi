package fr.devradar;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/radar")
public class RadarController {
    private final RadarService service;
    public RadarController(RadarService service) { this.service = service; }
    @GetMapping("/{username}")
    public Radar profile(@PathVariable String username) { return service.find(username); }
    @GetMapping("/{username}/score")
    public Map<String, Object> score(@PathVariable String username) {
        var radar = service.find(username);
        return Map.of("username", radar.username(), "score", radar.score(), "breakdown", radar.scoreBreakdown());
    }
    @GetMapping("/compare")
    public Map<String, Radar> compare(@RequestParam String a, @RequestParam String b) {
        return Map.of("a", service.find(a), "b", service.find(b));
    }
}
