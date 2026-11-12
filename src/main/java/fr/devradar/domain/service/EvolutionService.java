package fr.devradar.domain.service;
import fr.devradar.domain.model.Evolution;
public interface EvolutionService { Evolution history(String username, int days); }
