package fr.devradar.presentation.dto;

import fr.devradar.domain.model.Radar;

public record RadarScoreResponse(String username, int score, Radar.ScoreBreakdown breakdown) {
    public static RadarScoreResponse from(Radar radar) {
        return new RadarScoreResponse(radar.username(), radar.score(), radar.scoreBreakdown());
    }
}
