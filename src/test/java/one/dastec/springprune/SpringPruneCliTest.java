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
        assertTrue(output.contains("Summary of candidates for removal:"), "Output should list candidates");
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
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0</version>
                    </parent>
                    <artifactId>child1</artifactId>
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
}