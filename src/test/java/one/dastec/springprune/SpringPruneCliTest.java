package one.dastec.springprune;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpringPruneCliTest {

    private SpringPruneCli app;
    private CommandLine cmd;
    private StringWriter outWriter;
    private StringWriter errWriter;

    @BeforeEach
    void setUp() {
        app = new SpringPruneCli();
        cmd = new CommandLine(app);
        outWriter = new StringWriter();
        errWriter = new StringWriter();
        // Silencing output during tests to keep logs clean
        cmd.setOut(new PrintWriter(outWriter));
        cmd.setErr(new PrintWriter(errWriter));
    }

    @Test
    void test_shouldFailWhenPathDoesNotExist() {
        // Given an invalid directory path
        String[] args = {"--path", "/non/existent/path/dir"};

        // When executing the CLI
        int exitCode = cmd.execute(args);

        // Then it should return error code 1
        assertEquals(1, exitCode);
    }

    @Test
    void test_shouldHandleMissingPomGracefully(@TempDir Path tempProjectDir) {
        // Given a real directory but missing a pom.xml
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString()};

        // When executing the CLI
        int exitCode = cmd.execute(args);

        // Then it should return 0 and an informative message
        assertEquals(0, exitCode);
        assertTrue(outWriter.toString().contains("No Maven modules found"), "Should inform user that no modules were found");
    }

    @Test
    void test_shouldPassValidationWithValidEmptyPom(@TempDir Path tempProjectDir) throws IOException {
        // Given a real directory containing a baseline empty pom.xml
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String basicPomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                </project>
                """;
        Files.writeString(pomPath, basicPomContent);

        // We run in dry-run mode so it executes analysis pathways without breaking on background maven builds
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};

        // When executing the CLI
        int exitCode = cmd.execute(args);

        // Then it should run completely through to code 0 (success) because no unused targets exist
        assertEquals(0, exitCode);
    }

    @Test
    void test_shouldAcceptCustomSettingsOption(@TempDir Path tempProjectDir) throws IOException {
        // Given a project and a custom settings.xml
        Path pomPath = tempProjectDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                </project>
                """);

        Path settingsPath = tempProjectDir.resolve("custom-settings.xml");
        Files.writeString(settingsPath, "<settings></settings>");

        // When executing with the --settings option
        String[] args = {
                "--path", tempProjectDir.toAbsolutePath().toString(),
                "--settings", settingsPath.toAbsolutePath().toString(),
                "--dry-run"
        };
        int exitCode = cmd.execute(args);

        // Then it should execute successfully
        assertEquals(0, exitCode);
        assertTrue(outWriter.toString().contains("Starting safe dependency analyzer"), "Should start analysis even with custom settings");
    }

    @Test
    void test_shouldFailWhenSettingsPathDoesNotExist(@TempDir Path tempProjectDir) {
        // Given an invalid settings path
        String[] args = {
                "--path", tempProjectDir.toAbsolutePath().toString(),
                "--settings", "/non/existent/settings.xml"
        };

        // When executing the CLI
        int exitCode = cmd.execute(args);

        // Then it should return error code 1
        assertEquals(1, exitCode);
        assertTrue(errWriter.toString().contains("Provided settings path does not exist"), "Should show error for missing settings file");
    }

    @Test
    void test_shouldBackupPomFileBeforeModification(@TempDir Path tempProjectDir) throws IOException {
        // Given a mock project structure
        Path pomPath = tempProjectDir.resolve("pom.xml");
        Files.writeString(pomPath, "<project></project>");

        // Setup a dummy resources dir so scanners run smoothly
        Path resourcesDir = tempProjectDir.resolve("src").resolve("main").resolve("resources");
        Files.createDirectories(resourcesDir);

        // Triggering the backup creation directly via the test harness
        one.dastec.springprune.analyzer.BuildVerifier.createBackup(tempProjectDir);

        // Then the backup file (.bak) must exist side-by-side in the scratch path
        Path backupFile = tempProjectDir.resolve("pom.xml.bak");
        assertTrue(Files.exists(backupFile), "Backup file pom.xml.bak should be created.");
    }

    @Test
    void test_shouldIdentifyUnusedDependency(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with an unused dependency
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        // And a Java file that does NOT use the dependency
        Path javaDir = tempProjectDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), "public class App {}");

        // When running in dry-run mode
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should succeed and find the unused dependency
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("SUMMARY OF CANDIDATES FOR REMOVAL"), "Output should list candidates");
        assertTrue(output.contains("org.apache.commons:commons-lang3"), "Should identify commons-lang3 as unused");
    }

    @Test
    void test_shouldNotPruneUsedDependency(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with a used dependency
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        // And a Java file that DOES use the dependency
        Path javaDir = tempProjectDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), "import org.apache.commons.lang3.StringUtils; public class App {}");

        // When running in dry-run mode
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should succeed and find NO unused dependencies
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("Zero unused dependencies found"), "Should find no unused dependencies");
    }

    @Test
    void test_shouldKeepExplicitlyKeptDependency(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with an unused dependency BUT with a keep comment
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <!-- spring-prune:keep -->
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        Path javaDir = tempProjectDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), "public class App {}");

        // When running in dry-run mode
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should find NO unused dependencies because it's protected
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("Zero unused dependencies found"), "Should protect dependency with keep comment");
    }

    @Test
    void test_shouldKeepImplicitlyUsedDatabaseDriver(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with an unused DB driver BUT used in application.properties
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <version>42.6.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        Path resourcesDir = tempProjectDir.resolve("src/main/resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve("application.properties"), "spring.datasource.url=jdbc:postgresql://localhost:5432/db");

        Path javaDir = tempProjectDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), "public class App {}");

        // When running in dry-run mode
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should find NO unused dependencies because it's protected by config
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("Zero unused dependencies found"), "Should protect DB driver used in config");
    }

    @Test
    void test_shouldProcessMultiModuleProject(@TempDir Path tempProjectDir) throws IOException {
        // Parent POM
        Path parentPomPath = tempProjectDir.resolve("pom.xml");
        String parentPomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>child1</module>
                    </modules>
                </project>
                """;
        Files.writeString(parentPomPath, parentPomContent);

        // Child Module
        Path childDir = tempProjectDir.resolve("child1");
        Files.createDirectories(childDir);
        Path childPomPath = childDir.resolve("pom.xml");
        String childPomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>child1</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(childPomPath, childPomContent);

        // Child Source (does not use commons-lang3)
        Path javaDir = childDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("ChildApp.java"), "package com.test.child1; public class ChildApp {}");

        // When running the CLI on the PARENT directory
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should identify the unused dependency in the child module
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("org.apache.commons:commons-lang3"), "Should identify unused dependency in child module");
    }

    @Test
    void test_shouldHandleMissingRootPomWithSubmodules(@TempDir Path tempProjectDir) throws IOException {
        // Given a directory with no root pom.xml but a child module with one
        Path childDir = tempProjectDir.resolve("child1");
        Files.createDirectories(childDir);
        Path childPomPath = childDir.resolve("pom.xml");
        String childPomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>child1</artifactId>
                    <version>1.0</version>
                </project>
                """;
        Files.writeString(childPomPath, childPomContent);

        // When running the CLI on the root directory (dry-run to avoid calling real mvn)
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should succeed and find the module
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("Found 1 module(s)."), "Should find the child module even without root POM");
        assertTrue(output.contains("Analyzing module: child1"), "Should analyze the child module");
    }

    @Test
    void test_shouldVerifyModulesIndividuallyWhenRootPomIsMissing(@TempDir Path tempProjectDir) throws IOException {
        // Given a directory with no root pom.xml but a child module with one
        Path childDir = tempProjectDir.resolve("child1");
        Files.createDirectories(childDir);
        Path childPomPath = childDir.resolve("pom.xml");
        String childPomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>child1</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(childPomPath, childPomContent);
        
        // No source code uses it, so it should be identified as unused
        Path javaDir = childDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), "package com.test; public class App {}");

        // When running the CLI on the root directory (NOT dry-run)
        // Since root has no pom.xml, it should verify the child1 module individually.
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString()};
        
        int exitCode = cmd.execute(args);
        
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("No root pom.xml found. Verifying each modified module individually"), "Should use individual module verification");
        assertTrue(output.contains("Success! Project compiled cleanly"), "Should succeed verification");
    }

    @Test
    void test_shouldSuppressTransitiveReportWhenParentIsRemoved(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with a dependency that pulls in a transitive one
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.openrewrite</groupId>
                            <artifactId>rewrite-maven</artifactId>
                            <version>8.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        Path javaDir = tempProjectDir.resolve("src").resolve("main").resolve("java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), "public class App {}");

        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        assertEquals(0, exitCode);
        String output = outWriter.toString();
        
        // Should identify rewrite-maven as DIRECT
        assertTrue(output.contains("org.openrewrite:rewrite-maven"), "Should identify rewrite-maven");
        assertTrue(output.contains("DIRECT"), "Should identify rewrite-maven as DIRECT");
        
        // Should NOT identify rewrite-core because rewrite-maven is being removed
        assertFalse(output.contains("org.openrewrite:rewrite-core"), "Should suppress rewrite-core because its parent rewrite-maven is being removed");
    }

    @Test
    void test_shouldApplyTransitiveExclusion(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with a direct dependency and a transitive dependency
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.openrewrite</groupId>
                            <artifactId>rewrite-maven</artifactId>
                            <version>8.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        // We'll simulate the report that findUnusedDetailed would produce
        one.dastec.springprune.analyzer.OpenRewriteAnalyzer.DepReport transitiveReport = 
            new one.dastec.springprune.analyzer.OpenRewriteAnalyzer.DepReport(
                "org.openrewrite", "rewrite-core", false, "org.openrewrite:rewrite-maven"
        );

        // When applying exclusions
        one.dastec.springprune.analyzer.OpenRewriteAnalyzer.applyExclusions(tempProjectDir, java.util.Collections.singleton(transitiveReport), false, null);

        // Then the pom.xml should contain an <exclusion>
        String updatedPom = Files.readString(pomPath);
        assertTrue(updatedPom.contains("<exclusion>"), "POM should contain an exclusion tag");
        assertTrue(updatedPom.contains("<artifactId>rewrite-core</artifactId>"), "Should exclude rewrite-core");
    }


    @Test
    void test_shouldProtectCommonRuntimeEssentials(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with spring-jcl (logging bridge)
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework</groupId>
                            <artifactId>spring-jcl</artifactId>
                            <version>6.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        // No code uses it
        Path javaDir = tempProjectDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), "public class App {}");

        // When running in dry-run mode
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should NOT find spring-jcl as unused
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("Zero unused dependencies found"), "Should protect spring-jcl as common runtime essential");
    }

    @Test
    void test_shouldProtectTomcatEmbeddedServer(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with tomcat-embed-core
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.tomcat.embed</groupId>
                            <artifactId>tomcat-embed-core</artifactId>
                            <version>10.1.19</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        // No code uses it
        Path javaDir = tempProjectDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), "public class App {}");

        // When running in dry-run mode
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should NOT find tomcat-embed-core as unused
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("Zero unused dependencies found"), "Should protect tomcat-embed-core as common runtime essential");
    }

    @Test
    void test_shouldProtectJacksonWhenRestControllerIsPresent(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with jackson-databind
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.2</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        // Java code uses @RestController but does NOT import anything from Jackson
        Path javaDir = tempProjectDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("MyController.java"), """
                package com.test;
                import org.springframework.web.bind.annotation.RestController;
                
                @RestController
                public class MyController {
                    public String hello() { return "world"; }
                }
                """);

        // When running in dry-run mode
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should NOT find jackson-databind as unused
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("Zero unused dependencies found"), "Should protect jackson-databind via @RestController");
    }

    @Test
    void test_shouldProtectJpaAndLombokAnnotations(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with hibernate-core and lombok
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.hibernate.orm</groupId>
                            <artifactId>hibernate-core</artifactId>
                            <version>6.2.7.Final</version>
                        </dependency>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>1.18.28</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        // Java code uses @Entity and @Data
        Path javaDir = tempProjectDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("User.java"), """
                package com.test;
                import jakarta.persistence.Entity;
                import lombok.Data;
                
                @Entity
                @Data
                public class User {
                    private Long id;
                }
                """);

        // When running in dry-run mode
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should NOT find hibernate-core or lombok as unused
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("Zero unused dependencies found"), "Should protect JPA and Lombok via annotations");
    }
    @Test
    void test_shouldIdentifyUnusedSpringAiDependency(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with an unused Spring AI dependency
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.ai</groupId>
                            <artifactId>spring-ai-autoconfigure-model-ollama</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                            <version>3.2.5</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        // Code uses Spring Boot but NOT Spring AI
        Path javaDir = tempProjectDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), """
                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                
                @SpringBootApplication
                public class App {
                    public static void main(String[] args) {
                        SpringApplication.run(App.class, args);
                    }
                }
                """);

        // When running in dry-run mode
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should identify spring-ai-autoconfigure-model-ollama as unused
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("org.springframework.ai:spring-ai-autoconfigure-model-ollama"), 
            "Should identify unused Spring AI dependency");
    }
    @Test
    void test_shouldCorrectlyIdentifyGuavaAsUsedViaMismatchedPackage(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with Guava (groupId com.google.guava, package com.google.common)
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>31.1-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        // Code uses com.google.common.Lists which is in guava artifact
        Path javaDir = tempProjectDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), """
                import com.google.common.collect.Lists;
                import java.util.List;
                
                public class App {
                    public void test() {
                        List<String> list = Lists.newArrayList("a", "b");
                    }
                }
                """);

        // When running in dry-run mode
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--dry-run"};
        int exitCode = cmd.execute(args);

        // Then it should NOT find guava as unused because of the 2-part fallback (com.google)
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("Zero unused dependencies found"), "Should protect Guava via com.google fallback");
    }

    @Test
    void test_shouldCommentOutUnusedDependency(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with an unused dependency
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        one.dastec.springprune.analyzer.OpenRewriteAnalyzer.DepReport report = 
            new one.dastec.springprune.analyzer.OpenRewriteAnalyzer.DepReport(
                "org.apache.commons", "commons-lang3", true, "Directly declared in pom.xml"
        );

        // When applying exclusions with commentOnly = true
        one.dastec.springprune.analyzer.OpenRewriteAnalyzer.applyExclusions(tempProjectDir, java.util.Collections.singleton(report), true, null);

        // Then the dependency should be commented out in the POM
        String updatedPom = Files.readString(pomPath);
        assertTrue(updatedPom.contains("<!--"), "Should contain XML comment start");
        assertTrue(updatedPom.contains("-->"), "Should contain XML comment end");
        assertTrue(updatedPom.contains("<artifactId>commons-lang3</artifactId>"), "Should still contain the artifactId inside the comment");
    }

    @Test
    void test_shouldAddCommentedExclusion(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with a dependency that pulls in transitive
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.openrewrite</groupId>
                            <artifactId>rewrite-maven</artifactId>
                            <version>8.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        one.dastec.springprune.analyzer.OpenRewriteAnalyzer.DepReport transitiveReport = 
            new one.dastec.springprune.analyzer.OpenRewriteAnalyzer.DepReport(
                "org.openrewrite", "rewrite-core", false, "org.openrewrite:rewrite-maven"
        );

        // When applying exclusions with commentOnly = true
        one.dastec.springprune.analyzer.OpenRewriteAnalyzer.applyExclusions(tempProjectDir, java.util.Collections.singleton(transitiveReport), true, null);

        // Then the exclusion should be added but commented out
        String updatedPom = Files.readString(pomPath);
        assertTrue(updatedPom.contains("<!--"), "Should contain XML comment start");
        assertTrue(updatedPom.contains("<exclusion>"), "Should contain exclusion tag");
        assertTrue(updatedPom.contains("</exclusion>"), "Should contain exclusion closing tag");
        
        int commentStart = updatedPom.indexOf("<!--");
        int exclusionStart = updatedPom.indexOf("<exclusion>");
        int exclusionEnd = updatedPom.indexOf("</exclusion>");
        int commentEnd = updatedPom.indexOf("-->");
        
        assertTrue(commentStart < exclusionStart, "Comment should start before exclusion");
        assertTrue(exclusionEnd < commentEnd, "Comment should end after exclusion");
    }

    @Test
    void test_shouldShowCommentActionsInSummary(@TempDir Path tempProjectDir) throws IOException {
        // Given a project with an unused dependency
        Path pomPath = tempProjectDir.resolve("pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        // Code uses NOTHING
        Path javaDir = tempProjectDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), "public class App {}");

        // When running with --comment and --dry-run
        String[] args = {"--path", tempProjectDir.toAbsolutePath().toString(), "--comment", "--dry-run"};
        cmd.execute(args);

        // Then it should show "Comment out" in the output
        String output = outWriter.toString();
        assertTrue(output.contains("💬 Comment out in <dependencies>"), "Should show comment action for direct dependency");
    }
}