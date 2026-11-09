# DevRadar API

Transformer un pseudo GitHub en aperçu lisible de son activité **publique**.
Java 21 · Spring Boot 3.5 · Caffeine · Flyway · JDBC · PostgreSQL / H2.

## Démarrer

```powershell
$env:GITHUB_TOKEN = 'votre-token' # facultatif, uniquement côté serveur
mvn spring-boot:run
```

Sans configuration SQL, H2 démarre en mémoire : les instantanés sont perdus au redémarrage.
Pour PostgreSQL, définir `DATABASE_URL=jdbc:postgresql://hôte:5432/devradar`,
`DATABASE_USER` et `DATABASE_PASSWORD`. Avec Docker : définir `DATABASE_PASSWORD`
puis `docker compose up --build`.

Documentation : http://localhost:8080/swagger-ui.html · Santé : http://localhost:8080/actuator/health

## Routes

| GET | Résultat |
| --- | --- |
| `/api/v1/radar/{username}` | Carte, limites, date de collecte et détail du score |
| `/api/v1/radar/{username}/score` | Score et composantes |
| `/api/v1/radar/compare?a=alice&b=bob` | Deux cartes, sans désigner de gagnant |

## Ce que signifie le score

Signal de visibilité publique, **pas une évaluation du talent**.
Quatre composantes sur 25 : nombre de dépôts originaux (`min(25, 2 × n)`),
étoiles (`min(25, round(5 × log10(1 + étoiles)))`), événements observés
(`min(25, n)`), descriptions présentes (`round(25 × proportion)`).
Les descriptions ne prouvent pas la qualité de la documentation.
Langages = langage principal par dépôt, pas nombre de lignes de code.
Maximum 500 dépôts récents ; forks exclus. Événements publics : maximum 300 sur 30 jours,
sans garantie d'exhaustivité ni de temps réel. La série est en UTC.

## Exploitation

Cache borné à 1 000 profils pendant 15 minutes ; erreurs non cachées.
20 requêtes par minute et adresse distante, compteur local au processus.
Derrière un proxy, toutes les requêtes peuvent partager son adresse : configurer une
limitation au niveau du proxy de confiance pour la production. Ne pas accepter aveuglément X-Forwarded-For.
`ALLOWED_ORIGINS` : origines séparées par des virgules, défaut `http://localhost:3000`.
Le token GitHub n'est jamais transmis au client.
La base conserve uniquement le dernier score et la date par pseudo, sans endpoint public d'historique.

```text
Flutter → REST → Controller → Service → GitHub REST
                              ├ Caffeine (15 min)
                              └ SnapshotStore → SQL (Flyway)
```

## Vérifier

`mvn verify` : calcul, forks exclus, cache, validation, démarrage SQL, santé et CORS.
CI fournie ; aucun déploiement ni badge de succès n'est revendiqué avant exécution distante.
Le temps de réponse dépend de GitHub et d'un éventuel démarrage à froid : aucune garantie de 2 secondes.
Des guides détaillés `.md` sont disponibles localement mais exclus de Git, conformément au choix du projet.

## Organisation du code

`domain/model` et `domain/service` portent le modèle et le contrat métier.
`data/implementation` implémente le service ; `data/repository` encapsule GitHub et SQL.
`presentation/controller`, `presentation/dto` et `presentation/config` exposent l'API.
Le contrôleur dépend de l'interface du domaine. La carte est réutilisée sans entity en double.
