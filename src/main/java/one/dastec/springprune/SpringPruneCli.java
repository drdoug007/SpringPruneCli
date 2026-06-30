package one.dastec.springprune;

import one.dastec.springprune.analyzer.BuildVerifier;
import one.dastec.springprune.analyzer.OpenRewriteAnalyzer;
import one.dastec.springprune.analyzer.PomCommentScanner;
import one.dastec.springprune.analyzer.SpringConfigScanner;
import one.dastec.springprune.analyzer.SpringAnnotationScanner;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(
        name = "spring-prune",
        mixinStandardHelpOptions = true,
        version = "spring-prune 1.0",
        description = "Safely cleans up unused dependencies in Spring Boot applications."
)
public class SpringPruneCli implements Callable<Integer> {

    @Option(names = {"-p", "--path"}, description = "Target Spring Boot project root directory.", required = true)
    private Path projectPath;

    @Option(names = {"-d", "--dry-run"}, description = "Scan and print results without modifying pom.xml.")
    private boolean dryRun = false;

    @Option(names = {"-c", "--comment"}, description = "Comment out unused dependencies instead of removing them.")
    private boolean comment = false;

    @Option(names = {"-s", "--settings"}, description = "Optional path to a custom Maven settings.xml file.")
    private Path settingsPath;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        if (!Files.exists(projectPath) || !Files.isDirectory(projectPath)) {
            err.println("❌ Error: Provided path does not exist or is not a directory.");
            return 1;
        }

        if (settingsPath != null && (!Files.exists(settingsPath) || !Files.isRegularFile(settingsPath))) {
            err.println("❌ Error: Provided settings path does not exist or is not a file: " + settingsPath);
            return 1;
        }

        List<Path> modules = findModules(projectPath);
        if (modules.isEmpty()) {
            out.println("ℹ️ Info: No Maven modules found in the target directory. Nothing to prune.");
            return 0;
        }

        out.println("🚀 Starting safe dependency analyzer on: " + projectPath.toAbsolutePath());
        out.println("📦 Found " + modules.size() + " module(s).");

        Map<Path, Map<String, OpenRewriteAnalyzer.DepReport>> allUnused = new HashMap<>();

        for (Path module : modules) {
            out.println("\n--- Analyzing module: " + projectPath.relativize(module) + " ---");

            // Phase 1 & 2: Build the Safe Lists
            out.println("🔍 Phase 1: Scanning pom.xml for explicit developer overrides...");
            Set<String> explicitKept = PomCommentScanner.findExplicitlyKeptDependencies(module);

            out.println("🔍 Phase 2: Scanning application properties for implicit framework intent...");
            Set<String> implicitKept = SpringConfigScanner.generateSafeList(module);

            out.println("🔍 Phase 2.2: Scanning Java annotations for implicit framework intent...");
            Set<String> annotationKept = SpringAnnotationScanner.scanAnnotations(module);

            Set<String> protectedDependencies = new HashSet<>();
            protectedDependencies.addAll(explicitKept);
            protectedDependencies.addAll(implicitKept);
            protectedDependencies.addAll(annotationKept);

            // Phase 3: Run static code import parsing
            out.println("🤖 Phase 3: Commencing OpenRewrite Deep Tree Analysis...");
            Map<String, OpenRewriteAnalyzer.DepReport> detailedCandidates = OpenRewriteAnalyzer.findUnusedDetailed(module, protectedDependencies, settingsPath);

            // Filter candidates against our safety protections
            protectedDependencies.forEach(detailedCandidates::remove);

            // Second pass: Filter out transitives whose direct parent is already being removed
            Set<String> keysToRemove = detailedCandidates.values().stream()
                    .filter(report -> !report.isDirect)
                    .filter(report -> detailedCandidates.containsKey(report.introducedBy))
                    .map(OpenRewriteAnalyzer.DepReport::getKey)
                    .collect(Collectors.toSet());
            keysToRemove.forEach(detailedCandidates::remove);

            if (!detailedCandidates.isEmpty()) {
                allUnused.put(module, detailedCandidates);
                out.println("✂️ Found " + detailedCandidates.size() + " candidate(s) ready for removal.");
            } else {
                out.println("🎉 Module is pristine! Zero unused dependencies found.");
            }
        }

        if (allUnused.isEmpty()) {
            out.println("\n🎉 Everything looks pristine! Zero unused dependencies found across all modules.");
            return 0;
        }

        out.println("\n📋 SUMMARY OF CANDIDATES FOR REMOVAL");
        for (Map.Entry<Path, Map<String, OpenRewriteAnalyzer.DepReport>> entry : allUnused.entrySet()) {
            Path modulePath = entry.getKey();
            Map<String, OpenRewriteAnalyzer.DepReport> moduleCandidates = entry.getValue();

            out.println("\n[" + projectPath.relativize(modulePath) + "]");
            out.println("==========================================================================================================");
            out.printf("%-50s | %-12s | %-40s\n", "ARTIFACT COORDINATES", "TYPE", "ACTION / EXCLUSION SOURCE");
            out.println("==========================================================================================================");

            for (OpenRewriteAnalyzer.DepReport report : moduleCandidates.values()) {
                String coords = report.groupId + ":" + report.artifactId;
                if (report.isDirect) {
                    String action = comment ? "💬 Comment out in <dependencies>" : "✂️ Remove completely from <dependencies>";
                    out.printf("%-50s | %-12s | %-40s\n", coords, "DIRECT", action);
                } else {
                    String action = comment ? "💬 Commented exclusion inside: " : "🛑 Exclude inside: ";
                    out.printf("%-50s | %-12s | %-40s\n", coords, "TRANSITIVE", action + report.introducedBy);
                }
            }
            out.println("==========================================================================================================");
        }

        if (dryRun) {
            out.println("\n[DRY RUN] Execution stopped. No files modified.");
            return 0;
        }

        // Phase 4 & 5: Back up, Modify, and Verify Build Soundness
        out.println("\n💾 Creating project file backups...");
        allUnused.keySet().forEach(BuildVerifier::createBackup);

        out.println("💾 Applying safe exclusions via OpenRewrite...");
        allUnused.forEach((path, candidates) -> OpenRewriteAnalyzer.applyExclusions(path, candidates.values(), comment, settingsPath));

        out.println("🧪 Phase 4: Verifying Build Integrity...");
        boolean buildSuccess = BuildVerifier.runTestCompile(projectPath, allUnused.keySet(), out, err, settingsPath);

        if (buildSuccess) {
            out.println("\n🎉 Success! Project compiled cleanly. Deleting old footprint safely.");
            BuildVerifier.cleanupBackup();
            return 0;
        } else {
            out.println("\n⚠️ Warning: Build compilation failed after pruning. Initiating automatic rollback...");
            BuildVerifier.rollback();
            return 1;
        }
    }

    private List<Path> findModules(Path root) throws Exception {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("\\target\\"))
                    .map(Path::getParent)
                    .collect(Collectors.toList());
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SpringPruneCli()).execute(args);
        System.exit(exitCode);
    }
}