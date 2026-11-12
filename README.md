# DevRadar API

Service Spring Boot qui rassemble les projets, les langages et l’activité publique d’un compte GitHub.
Il alimente l’application DevRadar et permet de comparer deux profils.

**Java 21 · Spring Boot 3.5 · Caffeine · Flyway · PostgreSQL / H2**

## Lancer en local

Java 21 et Maven sont nécessaires.

```powershell
mvn spring-boot:run
```

Le service démarre sur `http://localhost:8080`, avec une base H2 en mémoire.
Un token GitHub peut être fourni via la variable `GITHUB_TOKEN` pour bénéficier d’un quota plus élevé. Il reste côté serveur.

- [Documentation des routes](http://localhost:8080/swagger-ui.html)
- [État du service](http://localhost:8080/actuator/health)

## Routes

| Requête GET | Résultat |
| --- | --- |
| `/api/v1/radar/{username}` | Profil, projets, langages et activité |
| `/api/v1/radar/{username}/score` | Score et détail du calcul |
| `/api/v1/radar/compare?a=alice&b=bob` | Données des deux profils |

## Comprendre les résultats

Le score sur 100 décrit la visibilité publique d’un compte. Il ne mesure pas les compétences d’une personne.

| Composante | Calcul, sur 25 points |
| --- | --- |
| Dépôts originaux | `min(25, 2 × nombre de dépôts)` |
| Étoiles | `min(25, round(5 × log10(1 + étoiles)))` |
| Activité | `min(25, nombre d’événements observés)` |
| Descriptions | `round(25 × proportion de dépôts décrits)` |

Les forks sont exclus. L’analyse porte sur 500 dépôts récents au maximum et jusqu’à 300 événements publics des 30 derniers jours.
Les langages correspondent au langage principal de chaque dépôt, pas à un volume de code. La série de jours d’activité est calculée en UTC.
Les projets privés et l’activité non exposée par GitHub ne sont pas pris en compte.

## Configuration

| Variable | Usage |
| --- | --- |
| `GITHUB_TOKEN` | Authentification des requêtes vers GitHub |
| `DATABASE_URL` | Adresse JDBC PostgreSQL ; H2 en mémoire par défaut |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Identifiants de la base |
| `ALLOWED_ORIGINS` | Origines web autorisées, séparées par des virgules ; défaut : `http://localhost:3000` |

Les profils sont mis en cache pendant 15 minutes, dans la limite de 1 000 entrées. Les erreurs ne sont pas mises en cache.
La base conserve le dernier score et sa date de calcul pour chaque pseudo.
La limite de requêtes est de 20 par minute et par adresse distante, pour chaque processus.
Derrière un proxy, une limitation adaptée doit être appliquée au niveau du proxy de confiance.

## Organisation

- `domain` : modèles et contrats métier.
- `data` : collecte GitHub, cache et accès SQL.
- `presentation` : contrôleurs REST, DTO et configuration HTTP.

## Tests

```powershell
mvn verify
```

Les tests couvrent le calcul du score, l’exclusion des forks, le cache, la validation des pseudos, SQL, la santé du service et CORS.

## Analyses complémentaires

Le calcul historique du score reste compatible. Les nouvelles analyses sont déclenchées à la demande et donnent leurs sources et leurs limites.

| Route GET | Résultat |
| --- | --- |
| `/api/v1/radar/{username}/intelligence` | Profil enrichi et indicateurs documentés |
| `/api/v1/repositories/{owner}/{repo}/intelligence` | Technologies et indices de maturité du dépôt |
| `/api/v1/radar/{username}/evolution?days=30` | Observations réellement enregistrées |
| `/api/v1/radar/{username}/wrapped?year=2026` | Bilan des observations de l’année |
| `/api/v1/discovery` | Recherche GitHub paginée |
| `/api/v1/discovery/analyzed` | Filtres sur les analyses présentes en cache |

L’analyse d’un dépôt examine au plus trois manifestes racine de 100 Kio chacun et un arbre limité à 2 000 entrées. Chaque requête entrante dispose d’un budget partagé de 18 appels GitHub et 30 secondes. Les analyses sont mises en cache et les refus de quota respectent un délai avant nouvel appel.

L’historique ajoute au plus une observation complète par jour et par compte, pendant 365 jours, avec une borne globale de 5 000 observations. Les jours manquants ne sont pas inventés. Une migration Flyway additive conserve la table existante.

L’ensemble utilise le service Spring Boot et la base existante, sans tâche planifiée ni infrastructure supplémentaire. Une technologie détectée ou un score ne prouve pas la maîtrise professionnelle d’une personne. Les filtres techniques portent sur les analyses encore en cache ; ils ne constituent pas un index exhaustif de GitHub.

La recherche de profils accepte `q` (nom, pseudo, `@pseudo` ou lien GitHub), avec des filtres `language`, `location`, `minimumRepos` et `page`. Le tri par défaut est la pertinence ; `sort=followers|repositories|joined` permet un tri explicite. Les filtres se cumulent. Les recherches sont mises en cache, sans enrichissement automatique des comptes retournés.
