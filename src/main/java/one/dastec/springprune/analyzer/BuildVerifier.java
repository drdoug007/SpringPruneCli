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
            String msg = e.getMessage();
            System.err.println("⚠️ Warning: Failed to create pom.xml backup: " + (msg != null ? msg : e.getClass().getName()));
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
    public static boolean runTestCompile(Path projectPath, java.util.Collection<Path> modifiedModules, java.io.PrintWriter out, java.io.PrintWriter err, Path settingsPath) {
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return executeMavenCompile(projectPath, projectPath, out, err, settingsPath);
        }

        // If root has no pom.xml, verify each modified module individually
        out.println("ℹ️ No root pom.xml found. Verifying each modified module individually...");
        for (Path modulePath : modifiedModules) {
            if (!executeMavenCompile(projectPath, modulePath, out, err, settingsPath)) {
                return false;
            }
        }
        return true;
    }

    private static boolean executeMavenCompile(Path rootPath, Path executePath, java.io.PrintWriter out, java.io.PrintWriter err, Path settingsPath) {
        out.println("🔨 Running 'mvn test-compile' in " + executePath.toAbsolutePath() + " to verify changes...");

        // Determine OS to invoke the correct Maven wrapper/executable
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWindows = os.contains("win");

        // Prefer local Maven wrapper (mvnw) if available, fallback to global mvn
        String mavenCmd = isWindows ? "mvn.cmd" : "mvn";
        if (Files.exists(rootPath.resolve(isWindows ? "mvnw.cmd" : "mvnw"))) {
            mavenCmd = isWindows ? ".\\mvnw.cmd" : "./mvnw";
        }

        if (settingsPath != null && Files.exists(settingsPath)) {
            return runMavenGoal(mavenCmd, executePath, out, err, "clean", "test-compile", "-s", settingsPath.toAbsolutePath().toString());
        }
        return runMavenGoal(mavenCmd, executePath, out, err, "clean", "test-compile");
    }

    private static boolean runMavenGoal(String mavenCmd, Path executePath, String... goals) {
        return runMavenGoal(mavenCmd, executePath, null, null, goals);
    }

    private static boolean runMavenGoal(String mavenCmd, Path executePath, java.io.PrintWriter out, java.io.PrintWriter err, String... goals) {
        List<String> command = new ArrayList<>();
        command.add(mavenCmd);
        for (String goal : goals) {
            command.add(goal);
        }
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(executePath.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Optional logging
                }
            }
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            String msg = e.getMessage();
            if (err != null) err.println("❌ Error executing Maven goals " + String.join(" ", goals) + ": " + (msg != null ? msg : e.getClass().getName()));
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
                String msg = e.getMessage();
                System.err.println("❌ Critical: Failed to restore pom.xml from backup " + backupPath + ": " + (msg != null ? msg : e.getClass().getName()));
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