package one.dastec.springprune.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class AnnotationScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void testScanAnnotations() throws IOException {
        Path javaDir = tempDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("User.java"), """
                package com.test;
                import jakarta.persistence.Entity;
                import lombok.Data;
                
                @Entity
                @Data
                public class User {
                }
                """);

        Set<String> protectedDeps = SpringAnnotationScanner.scanAnnotations(tempDir);
        
        assertTrue(protectedDeps.contains("org.hibernate.orm:hibernate-core"), "Should protect Hibernate");
        assertTrue(protectedDeps.contains("org.projectlombok:lombok"), "Should protect Lombok");
        assertTrue(protectedDeps.contains("com.fasterxml.jackson.core:jackson-databind") == false, "Should NOT protect Jackson");
    }
}
