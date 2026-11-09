package fr.devradar.domain.service;

import fr.devradar.domain.model.Radar;

/** Contrat métier, indépendant de Spring et des moyens de collecte. */
public interface RadarService {
    Radar find(String username);
}
