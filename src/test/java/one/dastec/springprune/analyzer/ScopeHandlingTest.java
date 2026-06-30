package one.dastec.springprune.analyzer;

import one.dastec.springprune.analyzer.OpenRewriteAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScopeHandlingTest {

    @TempDir
    Path tempDir;

    @Test
    void test_shouldIgnoreVariousScopes() throws IOException {
        Path pomPath = tempDir.resolve("pom.xml");
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
                            <scope>compile</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter-api</artifactId>
                            <version>5.8.2</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <version>42.3.3</version>
                            <scope>runtime</scope>
                        </dependency>
                        <dependency>
                            <groupId>jakarta.servlet</groupId>
                            <artifactId>jakarta.servlet-api</artifactId>
                            <version>5.0.0</version>
                            <scope>provided</scope>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(pomPath, pomContent);

        // Code uses NOTHING
        Path javaDir = tempDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), "public class App {}");

        Map<String, OpenRewriteAnalyzer.DepReport> result = OpenRewriteAnalyzer.findUnusedDetailed(tempDir, new HashSet<>());

        // We expect commons-lang3 to be found (it is compile scope and unused)
        assertTrue(result.containsKey("org.apache.commons:commons-lang3"), "Should identify unused compile dependency");

        // We want to check if test, runtime, and provided are ignored
        assertFalse(result.containsKey("org.junit.jupiter:junit-jupiter-api"), "Should ignore test dependency");
        assertFalse(result.containsKey("org.postgresql:postgresql"), "Should ignore runtime dependency");
        assertFalse(result.containsKey("jakarta.servlet:jakarta.servlet-api"), "Should ignore provided dependency");
    }
}
