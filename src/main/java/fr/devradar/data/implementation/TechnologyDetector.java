package fr.devradar.data.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.devradar.domain.model.RepositoryIntelligence.Detection;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import java.io.StringReader;

/** Lecture statique : aucun script, chargement de classe ou lien externe n'est exécuté. */
public final class TechnologyDetector {
    private TechnologyDetector() {}
    private record Rule(String dependency, String technology, String domain) {}
    private static final List<Rule> RULES = List.of(
        new Rule("spring-boot", "Spring Boot", "Backend"), new Rule("quarkus", "Quarkus", "Backend"),
        new Rule("micronaut", "Micronaut", "Backend"), new Rule("flutter", "Flutter", "Mobile"),
        new Rule("react-native", "React Native", "Mobile"), new Rule("@angular/core", "Angular", "Frontend"),
        new Rule("react", "React", "Frontend"), new Rule("vue", "Vue", "Frontend"), new Rule("next", "Next.js", "Frontend"),
        new Rule("express", "Express", "Backend"), new Rule("nestjs", "NestJS", "Backend"),
        new Rule("django", "Django", "Backend"), new Rule("fastapi", "FastAPI", "Backend"),
        new Rule("pandas", "pandas", "Data Engineering"), new Rule("apache-airflow", "Apache Airflow", "Data Engineering"),
        new Rule("torch", "PyTorch", "Machine Learning"), new Rule("tensorflow", "TensorFlow", "Machine Learning"),
        new Rule("ethers", "ethers", "Blockchain"), new Rule("web3", "Web3", "Blockchain"),
        new Rule("embedded-hal", "Embedded HAL", "Embedded Systems"), new Rule("yara-python", "YARA", "Cybersecurity"),
        new Rule("actix-web", "Actix Web", "Backend"), new Rule("axum", "Axum", "Backend"));
    public static List<Detection> detect(String path, String content, String repository, Instant at, String ref) {
        Set<String> dependencies = new HashSet<>();
        String method = "Dépendance déclarée dans le manifeste";
        try {
            if (path.equals("package.json")) {
                var json = new ObjectMapper().readTree(content);
                for (String key : List.of("dependencies", "devDependencies", "peerDependencies"))
                    json.path(key).fieldNames().forEachRemaining(dependencies::add);
            } else if (path.equals("pom.xml")) {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
                var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
                var nodes = document.getElementsByTagName("artifactId");
                for (int i = 0; i < nodes.getLength(); i++) dependencies.add(nodes.item(i).getTextContent().trim());
            } else if (path.equals("pubspec.yaml")) {
                var yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
                Object loaded = yaml.load(content);
                if (loaded instanceof Map<?, ?> map) {
                    for (String key : List.of("dependencies", "dev_dependencies")) {
                        if (map.get(key) instanceof Map<?, ?> deps) deps.keySet().forEach(dep -> dependencies.add(dep.toString()));
                    }
                }
            } else {
                // Ne conserver que les déclarations ; ignorer les commentaires et le nom du projet.
                content = content.replaceAll("(?m)^\\s*#.*$", "").replaceAll("(?m)//.*$", "");
                for (Rule rule : RULES) {
                    String token = Pattern.quote(rule.dependency());
                    boolean declared = Pattern.compile("(?im)(?:^|[^a-z0-9_-])" + token + "(?:[^a-z0-9_-]|$)").matcher(content).find();
                    if (declared) dependencies.add(rule.dependency());
                    if (rule.dependency().equals("spring-boot") && (content.contains("org.springframework.boot") || content.contains("spring-boot-starter"))) dependencies.add("spring-boot");
                }
                method = "Marqueur de dépendance dans le manifeste (lecture statique)";
            }
        } catch (Exception ex) { throw new IllegalArgumentException("Manifeste illisible", ex); }
        List<Detection> result = new ArrayList<>();
        for (Rule rule : RULES) {
            if (dependencies.stream().anyMatch(value -> value.equals(rule.dependency()) ||
                (rule.dependency().equals("spring-boot") && value.startsWith("spring-boot-")) ||
                (rule.dependency().equals("nestjs") && value.startsWith("@nestjs/"))))
                result.add(new Detection(rule.technology(), rule.domain(), repository, path, method,
                    path.equals("package.json") || path.equals("pom.xml") || path.equals("pubspec.yaml") ? "élevée" : "moyenne", at, ref));
        }
        return result;
    }
}
