package one.dastec.springprune.analyzer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class BuildVerifier {

    private static final List<Path> backupPaths = new ArrayList<>();

    /**
     * Creates a temporary backup copy of the current pom.xml before changes are applied.
     */
    public static void createBackup(Path modulePath) {
        Path pomPath = modulePath.resolve("pom.xml");
        Path backupPath = modulePath.resolve("pom.xml.bak");
        try {
            Files.copy(pomPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            backupPaths.add(backupPath);
        } catch (IOException e) {
            // Using standard error for critical backup failures
            System.err.println("⚠️ Warning: Failed to create pom.xml backup: " + e.getMessage());
        }
    }

    /**
     * Executes background Maven test compilations to verify build soundness.
     * @param projectPath Root directory of the target project.
     * @param modifiedModules The set of modules that were actually modified.
     * @param out PrintWriter for logging output.
     * @param err PrintWriter for logging errors.
     * @return true if the build compiled successfully, false otherwise.
     */
    public static boolean runTestCompile(Path projectPath, java.util.Collection<Path> modifiedModules, java.io.PrintWriter out, java.io.PrintWriter err) {
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return executeMavenCompile(projectPath, projectPath, out, err);
        }

        // If root has no pom.xml, verify each modified module individually
        out.println("ℹ️ No root pom.xml found. Verifying each modified module individually...");
        for (Path modulePath : modifiedModules) {
            if (!executeMavenCompile(projectPath, modulePath, out, err)) {
                return false;
            }
        }
        return true;
    }

    private static boolean executeMavenCompile(Path rootPath, Path executePath, java.io.PrintWriter out, java.io.PrintWriter err) {
        out.println("🔨 Running 'mvn test-compile' in " + executePath.toAbsolutePath() + " to verify changes...");

        // Determine OS to invoke the correct Maven wrapper/executable
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWindows = os.contains("win");

        // Prefer local Maven wrapper (mvnw) if available, fallback to global mvn
        String mavenCmd = isWindows ? "mvn.cmd" : "mvn";
        if (Files.exists(rootPath.resolve(isWindows ? "mvnw.cmd" : "mvnw"))) {
            mavenCmd = isWindows ? ".\\mvnw.cmd" : "./mvnw";
        }

        ProcessBuilder pb = new ProcessBuilder(mavenCmd, "clean", "test-compile");
        pb.directory(executePath.toFile());
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
            err.println("❌ Error executing Maven verification: " + e.getMessage());
            return false;
        }
    }

    /**
     * Restores all original pom.xml files from backups if verification fails.
     */
    public static void rollback() {
        if (backupPaths.isEmpty()) {
            System.err.println("❌ Critical: No backup files found to rollback to.");
            return;
        }

        for (Path backupPath : backupPaths) {
            Path pomPath = backupPath.getParent().resolve("pom.xml");
            try {
                Files.move(backupPath, pomPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("❌ Critical: Failed to restore pom.xml from backup " + backupPath + ": " + e.getMessage());
            }
        }
        System.out.println("🔄 Rollback successful. All pom.xml files have been restored to their original state.");
        backupPaths.clear();
    }

    /**
     * Cleans up all backup files when the optimization succeeds.
     */
    public static void cleanupBackup() {
        for (Path backupPath : backupPaths) {
            try {
                Files.deleteIfExists(backupPath);
            } catch (IOException ignored) {}
        }
        backupPaths.clear();
    }
}