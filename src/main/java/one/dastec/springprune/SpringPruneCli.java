package one.dastec.springprune;

import one.dastec.springprune.analyzer.BuildVerifier;
import one.dastec.springprune.analyzer.OpenRewriteAnalyzer;
import one.dastec.springprune.analyzer.PomCommentScanner;
import one.dastec.springprune.analyzer.SpringConfigScanner;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

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

    @Override
    public Integer call() throws Exception {
        if (!Files.exists(projectPath) || !Files.isDirectory(projectPath)) {
            System.err.println("❌ Error: Provided path does not exist or is not a directory.");
            return 1;
        }

        Path pomPath = projectPath.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            System.err.println("❌ Error: Target directory is not a Maven project (missing pom.xml).");
            return 1;
        }

        System.out.println("🚀 Starting safe dependency analyzer on: " + projectPath.toAbsolutePath());

        // Phase 1 & 2: Build the Safe Lists
        System.out.println("🔍 Phase 1: Scanning pom.xml for explicit developer overrides...");
        Set<String> explicitKept = PomCommentScanner.findExplicitlyKeptDependencies(projectPath);

        System.out.println("🔍 Phase 2: Scanning application properties for implicit framework intent...");
        Set<String> implicitKept = SpringConfigScanner.generateSafeList(projectPath);

        Set<String> protectedDependencies = new HashSet<>();
        protectedDependencies.addAll(explicitKept);
        protectedDependencies.addAll(implicitKept);

        // Phase 3: Run static code import parsing
        System.out.println("🤖 Phase 3: Commencing OpenRewrite Static Import Analysis...");
        Set<String> unusedCandidates = OpenRewriteAnalyzer.findUnused(projectPath);

        // Filter candidates against our safety protections
        unusedCandidates.removeAll(protectedDependencies);

        if (unusedCandidates.isEmpty()) {
            System.out.println("🎉 Everything looks pristine! Zero unused dependencies found.");
            return 0;
        }

        System.out.println("\n✂️ Found candidates ready for removal:");
        unusedCandidates.forEach(dep -> System.out.println("  • " + dep));

        if (dryRun) {
            System.out.println("\n[DRY RUN] Execution stopped. No files modified.");
            return 0;
        }

        // Phase 4 & 5: Back up, Modify, and Verify Build Soundness
        System.out.println("\n💾 Creating project file backup...");
        BuildVerifier.createBackup(projectPath);

        System.out.println("💾 Applying safe exclusions via OpenRewrite...");
        OpenRewriteAnalyzer.applyExclusions(projectPath, unusedCandidates);

        System.out.println("🧪 Phase 4: Verifying Build Integrity...");
        boolean buildSuccess = BuildVerifier.runTestCompile(projectPath);

        if (buildSuccess) {
            System.out.println("\n🎉 Success! Project compiled cleanly. Deleting old footprint safely.");
            BuildVerifier.cleanupBackup();
            return 0;
        } else {
            System.out.println("\n⚠️ Warning: Build compilation failed after pruning. Initiating automatic rollback...");
            BuildVerifier.rollback(projectPath);
            return 1;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SpringPruneCli()).execute(args);
        System.exit(exitCode);
    }
}