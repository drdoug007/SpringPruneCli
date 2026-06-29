package one.dastec.springprune.analyzer;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.maven.MavenParser;
import org.openrewrite.maven.RemoveDependency;
import org.openrewrite.xml.tree.Xml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class OpenRewriteAnalyzer {

    /**
     * Statically analyzes the project to find top-level dependencies that have zero code imports.
     * @param projectPath Root path of the target project.
     * @return A set of "groupId:artifactId" strings representing unused dependencies.
     */
    public static Set<String> findUnused(Path projectPath) {
        Set<String> unusedCandidates = new HashSet<>();
        Path pomPath = projectPath.resolve("pom.xml");

        ExecutionContext ctx = new InMemoryExecutionContext(t -> System.err.println("OpenRewrite Log: " + t.getMessage()));

        try {
            // 1. Parse the pom.xml using OpenRewrite's MavenParser
            MavenParser mavenParser = MavenParser.builder().build();
            List<SourceFile> mavenAstList = mavenParser.parse(Collections.singletonList(pomPath), projectPath, ctx)
                    .collect(Collectors.toList());

            if (mavenAstList.isEmpty()) {
                return unusedCandidates;
            }

            // 💡 FIX: Wrap the list into a LargeSourceSet
            org.openrewrite.LargeSourceSet sourceSet = new org.openrewrite.internal.InMemoryLargeSourceSet(mavenAstList);

            // Now you can safely extract your document or pass the sourceSet along
            Xml.Document pomDocument = (Xml.Document) mavenAstList.get(0);

            // 2. Extract explicit, top-level dependencies from the POM
            // For a production MVP, you can dig into pomDocument.getMarkers() to extract coordinates.
            // For this baseline, let's assume we extract them to find matches against our source code.
            List<String> declaredDependencies = extractDeclaredDependencies(pomDocument);

            // 3. Scan all Java files for imports
            Set<String> importPackages = scanJavaImports(projectPath);

            // 4. Cross-reference declared dependencies against imported packages
            for (String dep : declaredDependencies) {
                if (!isDependencyUsed(dep, importPackages)) {
                    unusedCandidates.add(dep);
                }
            }

        } catch (Exception e) {
            System.err.println("⚠️ Warning: OpenRewrite analysis encountered an error: " + e.getMessage());
        }

        return unusedCandidates;
    }

    /**
     * Uses OpenRewrite's RemoveDependency recipe to programmatically clean the pom.xml
     */
    public static void applyExclusions(Path projectPath, Set<String> dependenciesToRemove) {
        Path pomPath = projectPath.resolve("pom.xml");
        ExecutionContext ctx = new InMemoryExecutionContext();

        try {
            MavenParser mavenParser = MavenParser.builder().build();
            List<SourceFile> currentPomList = mavenParser.parse(Collections.singletonList(pomPath), projectPath, ctx)
                    .collect(Collectors.toList());

            for (String depId : dependenciesToRemove) {
                String[] parts = depId.split(":");
                String groupId = parts[0];
                String artifactId = parts[1];

                // 1. Wrap the current list into the required LargeSourceSet
                org.openrewrite.LargeSourceSet sourceSet = new org.openrewrite.internal.InMemoryLargeSourceSet(currentPomList);

                RemoveDependency removeRecipe = new RemoveDependency(groupId, artifactId, null);

                // 2. Run the recipe
                org.openrewrite.RecipeRun recipeRun = removeRecipe.run(sourceSet, ctx);

                // 💡 FIX: Access the results via the changeset collection
                List<org.openrewrite.Result> results = recipeRun.getChangeset().getAllResults();

                // 3. Apply the changes back to our local list for the next loop iteration
                if (!results.isEmpty()) {
                    currentPomList = currentPomList.stream()
                            .map(sourceFile -> {
                                for (org.openrewrite.Result result : results) {
                                    // Match by original file ID to swap in the updated version
                                    if (sourceFile.getId().equals(result.getBefore().getId())) {
                                        return result.getAfter();
                                    }
                                }
                                return sourceFile;
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                }
            }

// 5. Write the final, fully-pruned POM back to the disk
            if (!currentPomList.isEmpty() && currentPomList.get(0) != null) {
                Files.writeString(pomPath, currentPomList.get(0).printAll());
            }

        } catch (Exception e) {
            System.err.println("⚠️ Warning: Failed to apply modifications via OpenRewrite: " + e.getMessage());
        }
    }

    private static List<String> extractDeclaredDependencies(Xml.Document pomDocument) {
        // Simple placeholder array matching coordinates.
        // In a complete build, OpenRewrite resolves transitives via Maven resolution markers.
        return new ArrayList<>();
    }

    private static Set<String> scanJavaImports(Path projectPath) throws IOException {
        Set<String> imports = new HashSet<>();
        Path srcPath = projectPath.resolve("src").resolve("main").resolve("java");

        if (!Files.exists(srcPath)) return imports;

        Files.walk(srcPath)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(file -> {
                    try {
                        Files.lines(file)
                                .filter(line -> line.trim().startsWith("import "))
                                .map(line -> line.replace("import ", "").replace(";", "").trim())
                                .forEach(imports::add);
                    } catch (IOException ignored) {}
                });
        return imports;
    }

    private static boolean isDependencyUsed(String dependencyId, Set<String> importedPackages) {
        // Strategic heuristics map artifacts to their base package structures
        if (dependencyId.contains("commons-lang3")) return importedPackages.stream().anyMatch(i -> i.startsWith("org.apache.commons.lang3"));
        if (dependencyId.contains("guava")) return importedPackages.stream().anyMatch(i -> i.startsWith("com.google.common"));
        if (dependencyId.contains("jackson")) return importedPackages.stream().anyMatch(i -> i.startsWith("com.fasterxml.jackson"));
        return true; // Safe fallback default if the heuristic mapping isn't registered yet
    }
}
