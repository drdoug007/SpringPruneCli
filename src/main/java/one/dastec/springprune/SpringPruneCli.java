package one.dastec.springprune;

import one.dastec.springprune.analyzer.BuildVerifier;
import one.dastec.springprune.analyzer.OpenRewriteAnalyzer;
import one.dastec.springprune.analyzer.PomCommentScanner;
import one.dastec.springprune.analyzer.SpringConfigScanner;
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

        List<Path> modules = findModules(projectPath);
        if (modules.isEmpty()) {
            out.println("ℹ️ Info: No Maven modules found in the target directory. Nothing to prune.");
            return 0;
        }

        out.println("🚀 Starting safe dependency analyzer on: " + projectPath.toAbsolutePath());
        out.println("📦 Found " + modules.size() + " module(s).");

        Map<Path, Set<String>> allUnused = new HashMap<>();

        for (Path module : modules) {
            out.println("\n--- Analyzing module: " + projectPath.relativize(module) + " ---");

            // Phase 1 & 2: Build the Safe Lists
            out.println("🔍 Phase 1: Scanning pom.xml for explicit developer overrides...");
            Set<String> explicitKept = PomCommentScanner.findExplicitlyKeptDependencies(module);

            out.println("🔍 Phase 2: Scanning application properties for implicit framework intent...");
            Set<String> implicitKept = SpringConfigScanner.generateSafeList(module);

            Set<String> protectedDependencies = new HashSet<>();
            protectedDependencies.addAll(explicitKept);
            protectedDependencies.addAll(implicitKept);

            // Phase 3: Run static code import parsing
            out.println("🤖 Phase 3: Commencing OpenRewrite Static Import Analysis...");
            Set<String> unusedCandidates = OpenRewriteAnalyzer.findUnused(module);

            // Filter candidates against our safety protections
            unusedCandidates.removeAll(protectedDependencies);

            if (!unusedCandidates.isEmpty()) {
                allUnused.put(module, unusedCandidates);
                out.println("✂️ Found " + unusedCandidates.size() + " candidate(s) ready for removal.");
            } else {
                out.println("🎉 Module is pristine! Zero unused dependencies found.");
            }
        }

        if (allUnused.isEmpty()) {
            out.println("\n🎉 Everything looks pristine! Zero unused dependencies found across all modules.");
            return 0;
        }

        out.println("\nSummary of candidates for removal:");
        allUnused.forEach((path, deps) -> {
            out.println("[" + projectPath.relativize(path) + "]");
            deps.forEach(dep -> out.println("  • " + dep));
        });

        if (dryRun) {
            out.println("\n[DRY RUN] Execution stopped. No files modified.");
            return 0;
        }

        // Phase 4 & 5: Back up, Modify, and Verify Build Soundness
        out.println("\n💾 Creating project file backups...");
        allUnused.keySet().forEach(BuildVerifier::createBackup);

        out.println("💾 Applying safe exclusions via OpenRewrite...");
        allUnused.forEach(OpenRewriteAnalyzer::applyExclusions);

        out.println("🧪 Phase 4: Verifying Build Integrity...");
        boolean buildSuccess = BuildVerifier.runTestCompile(projectPath, allUnused.keySet(), out, err);

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