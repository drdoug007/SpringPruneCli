package one.dastec.springprune.analyzer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class BuildVerifier {

    private static Path backupPomPath;

    /**
     * Creates a temporary backup copy of the current pom.xml before changes are applied.
     */
    public static void createBackup(Path projectPath) {
        Path pomPath = projectPath.resolve("pom.xml");
        backupPomPath = projectPath.resolve("pom.xml.bak");
        try {
            Files.copy(pomPath, backupPomPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("⚠️ Warning: Failed to create pom.xml backup: " + e.getMessage());
        }
    }

    /**
     * Executes a background Maven test compilation to verify build soundness.
     * @param projectPath Root directory of the target project.
     * @return true if the build compiled successfully, false otherwise.
     */
    public static boolean runTestCompile(Path projectPath) {
        System.out.println("🔨 Running 'mvn test-compile' to verify changes...");

        // Determine OS to invoke the correct Maven wrapper/executable
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWindows = os.contains("win");

        // Prefer local Maven wrapper (mvnw) if available, fallback to global mvn
        String mavenCmd = isWindows ? "mvn.cmd" : "mvn";
        if (Files.exists(projectPath.resolve(isWindows ? "mvnw.cmd" : "mvnw"))) {
            mavenCmd = isWindows ? ".\\mvnw.cmd" : "./mvnw";
        }

        ProcessBuilder pb = new ProcessBuilder(mavenCmd, "clean", "test-compile");
        pb.directory(projectPath.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            // Stream the Maven output in the background to log failures if necessary
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Optional: Un-comment to see raw maven logs during debug
                    // System.out.println("[MAVEN] " + line);
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0;

        } catch (IOException | InterruptedException e) {
            System.err.println("❌ Error executing Maven verification: " + e.getMessage());
            return false;
        }
    }

    /**
     * Restores the original pom.xml from backup if verification fails.
     */
    public static void rollback(Path projectPath) {
        if (backupPomPath == null || !Files.exists(backupPomPath)) {
            System.err.println("❌ Critical: No backup file found to rollback to.");
            return;
        }

        Path pomPath = projectPath.resolve("pom.xml");
        try {
            Files.move(backupPomPath, pomPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("🔄 Rollback successful. pom.xml has been restored to its original state.");
        } catch (IOException e) {
            System.err.println("❌ Critical: Failed to restore pom.xml from backup: " + e.getMessage());
        }
    }

    /**
     * Cleans up the backup file when the optimization succeeds.
     */
    public static void cleanupBackup() {
        try {
            if (backupPomPath != null) {
                Files.deleteIfExists(backupPomPath);
            }
        } catch (IOException ignored) {}
    }
}