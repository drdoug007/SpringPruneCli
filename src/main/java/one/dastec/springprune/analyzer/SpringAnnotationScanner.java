package one.dastec.springprune.analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Scans Java source files for specific Spring and Jakarta annotations 
 * that imply the need for certain runtime dependencies.
 */
public class SpringAnnotationScanner {

    private static final Map<Pattern, String[]> ANNOTATION_TO_DEPENDENCIES = new HashMap<>();

    static {
        // Web / JSON support (Jackson is required for @RestController)
        ANNOTATION_TO_DEPENDENCIES.put(Pattern.compile("@(Rest)?Controller|@ResponseBody|@RequestMapping|@.*Mapping"),
                new String[]{
                    "com.fasterxml.jackson.core:jackson-databind",
                    "com.fasterxml.jackson.core:jackson-core",
                    "com.fasterxml.jackson.core:jackson-annotations",
                    "com.fasterxml.jackson.datatype:jackson-datatype-jdk8",
                    "com.fasterxml.jackson.datatype:jackson-datatype-jsr310",
                    "com.fasterxml.jackson.module:jackson-module-parameter-names",
                    "org.springframework.boot:spring-boot-starter-json",
                    "org.springframework.boot:spring-boot-starter-web"
                });

        // Persistence / JPA (Implies Hibernate and JAXB for some versions)
        ANNOTATION_TO_DEPENDENCIES.put(Pattern.compile("@Entity|@Table|@Id|@Column|@ManyToMany|@ManyToOne|@OneToMany|@OneToOne"),
                new String[]{
                    "org.hibernate.orm:hibernate-core",
                    "org.glassfish.jaxb:jaxb-runtime",
                    "jakarta.persistence:jakarta.persistence-api",
                    "org.springframework.boot:spring-boot-starter-data-jpa"
                });

        // Validation
        ANNOTATION_TO_DEPENDENCIES.put(Pattern.compile("@Valid|@NotNull|@Size|@Min|@Max|@Email"),
                new String[]{
                    "org.hibernate.validator:hibernate-validator",
                    "org.springframework.boot:spring-boot-starter-validation",
                    "jakarta.validation:jakarta.validation-api"
                });

        // Configuration Processor
        ANNOTATION_TO_DEPENDENCIES.put(Pattern.compile("@ConfigurationProperties"),
                new String[]{
                    "org.springframework.boot:spring-boot-configuration-processor"
                });

        // Lombok
        ANNOTATION_TO_DEPENDENCIES.put(Pattern.compile("@Data|@Getter|@Setter|@Builder|@NoArgsConstructor|@AllArgsConstructor|@Slf4j|@Log4j2"),
                new String[]{
                    "org.projectlombok:lombok"
                });
    }

    public static Set<String> scanAnnotations(Path modulePath) {
        Set<String> protectedDeps = new HashSet<>();
        Path srcPath = modulePath.resolve("src").resolve("main").resolve("java");
        
        if (!Files.exists(srcPath) || !Files.isDirectory(srcPath)) {
            return protectedDeps;
        }

        try {
            Files.walk(srcPath)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> {
                        try {
                            // Read first few KB only or entire file? For annotations, we usually need the class level or method level.
                            // Entire file is safer for now.
                            String content = Files.readString(file);
                            for (Map.Entry<Pattern, String[]> entry : ANNOTATION_TO_DEPENDENCIES.entrySet()) {
                                if (entry.getKey().matcher(content).find()) {
                                    Collections.addAll(protectedDeps, entry.getValue());
                                }
                            }
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            String msg = e.getMessage();
            System.err.println("⚠️ Warning: Failed to scan annotations in " + srcPath + ": " + (msg != null ? msg : e.getClass().getName()));
        }

        return protectedDeps;
    }
}
