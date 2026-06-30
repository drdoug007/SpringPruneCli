package one.dastec.springprune.analyzer;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.ExcludeDependency;
import org.openrewrite.maven.MavenParser;
import org.openrewrite.maven.RemoveDependency;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class OpenRewriteAnalyzer {

    // Rich data structure to track the context of the unused artifact
    public static class DepReport {
        public String groupId;
        public String artifactId;
        public boolean isDirect;       // True if declared in pom.xml, False if transitive
        public String introducedBy;    // Parent dependency if transitive

        public DepReport(String groupId, String artifactId, boolean isDirect, String introducedBy) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.isDirect = isDirect;
            this.introducedBy = introducedBy;
        }

        public String getKey() { return groupId + ":" + artifactId; }
    }

    public static Map<String, DepReport> findUnusedDetailed(Path projectPath, Set<String> protectedDependencies) {
        Map<String, DepReport> reportMap = new HashMap<>();
        Path pomPath = projectPath.resolve("pom.xml");
        ExecutionContext ctx = new InMemoryExecutionContext(t -> System.err.println("OpenRewrite Log: " + t.getMessage()));

        try {
            MavenParser mavenParser = MavenParser.builder().build();
            List<SourceFile> mavenAst = mavenParser.parse(Collections.singletonList(pomPath), projectPath, ctx)
                    .collect(Collectors.toList());

            if (mavenAst.isEmpty() || !(mavenAst.get(0) instanceof Xml.Document)) {
                return reportMap;
            }
            Xml.Document pomDocument = (Xml.Document) mavenAst.get(0);

            Optional<MavenResolutionResult> mavenResolution = pomDocument.getMarkers().findFirst(MavenResolutionResult.class);
            if (mavenResolution.isEmpty()) {
                return reportMap;
            }

            // Gather all compiled dependencies (both direct and transitive)
            List<ResolvedDependency> allDeps = mavenResolution.get()
                    .getDependencies()
                    .get(org.openrewrite.maven.tree.Scope.Compile);

            if (allDeps == null) {
                return reportMap;
            }

            // Get all types actively imported in code
            Set<String> typesUsedInCode = scanJavaTypesInUse(projectPath);

            for (ResolvedDependency dep : allDeps) {
                String groupId = dep.getGroupId();
                String artifactId = dep.getArtifactId();

                // Special handling for common runtime/bridge dependencies
                if (isCommonRuntimeEssential(groupId, artifactId)) {
                    continue;
                }

                if (isIgnoredScope(dep)) {
                    continue;
                }

                if (isDependencyUsed(dep, allDeps, typesUsedInCode, protectedDependencies)) {
                    continue;
                }

                // If not used, mark for removal/exclusion
                // Trace back lineage depth: depth == 0 means direct dependency in pom.xml
                boolean isDirect = (dep.getDepth() == 0);

                // Trace root path parent pulling this jar in
                String introducedBy = "Directly declared in pom.xml";
                if (!isDirect) {
                    for (ResolvedDependency rootDep : allDeps) {
                        if (rootDep.getDepth() == 0 && rootDep.findDependency(groupId, artifactId) != null) {
                            introducedBy = rootDep.getGroupId() + ":" + rootDep.getArtifactId();
                            break;
                        }
                    }
                }

                DepReport report = new DepReport(groupId, artifactId, isDirect, introducedBy);
                reportMap.put(report.getKey(), report);
            }
        } catch (Exception e) {
            System.err.println("⚠️ Warning: Analysis failed: " + e.getMessage());
        }
        return reportMap;
    }

    private static boolean isIgnoredScope(ResolvedDependency dep) {
        if (dep.getRequested() == null || dep.getRequested().getScope() == null) {
            return false;
        }
        String scope = dep.getRequested().getScope().toLowerCase();
        return scope.equals("test") || scope.equals("runtime") || scope.equals("provided");
    }

    private static boolean isDependencyUsed(ResolvedDependency dep, List<ResolvedDependency> allDeps, Set<String> typesUsedInCode, Set<String> protectedDependencies) {
        // Check if the dependency itself is used or protected
        if (isExplicitlyUsed(dep, typesUsedInCode, protectedDependencies)) {
            return true;
        }

        // Check if any root dependency that pulls this one in is used or protected
        for (ResolvedDependency rootDep : allDeps) {
            if (rootDep.getDepth() == 0 && rootDep.findDependency(dep.getGroupId(), dep.getArtifactId()) != null) {
                if (isExplicitlyUsed(rootDep, typesUsedInCode, protectedDependencies)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isExplicitlyUsed(ResolvedDependency dep, Set<String> typesUsedInCode, Set<String> protectedDependencies) {
        String groupId = dep.getGroupId();
        String artifactId = dep.getArtifactId();

        // Check if explicitly protected (by annotations, config, or comments)
        if (protectedDependencies.contains(groupId + ":" + artifactId)) {
            return true;
        }

        // 1. Try matching with full groupId (most accurate for modern libraries)
        if (typesUsedInCode.stream().anyMatch(type -> type.startsWith(groupId))) {
            return true;
        }

        // 2. Fallback to a refined namespace (at least 2 parts, but avoid broad umbrellas)
        if (groupId.contains(".")) {
            String[] parts = groupId.split("\\.");
            if (parts.length >= 2) {
                String baseNamespace = parts[0] + "." + parts[1];

                // Whitelist of broad organization umbrellas where 2-part matching is too aggressive.
                // For these, we prefer matching the more specific groupId rather than the top-level org.
                // e.g., org.springframework.ai should not be marked 'used' just because org.springframework.boot is.
                boolean isBroadUmbrella = baseNamespace.equals("org.springframework") ||
                                          baseNamespace.equals("org.apache") ||
                                          baseNamespace.equals("io.projectreactor");

                if (!isBroadUmbrella || parts.length == 2) {
                    if (typesUsedInCode.stream().anyMatch(type -> type.startsWith(baseNamespace))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Helper to read through types used in code rather than just string-parsing lines
     */
    private static Set<String> scanJavaTypesInUse(Path projectPath) throws Exception {
        Set<String> types = new HashSet<>();
        List<Path> scanPaths = new ArrayList<>();
        scanPaths.add(projectPath.resolve("src").resolve("main").resolve("java"));

        for (Path path : scanPaths) {
            if (!Files.exists(path)) continue;

            Files.walk(path)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> {
                        try {
                            Files.lines(file)
                                    .forEach(line -> {
                                        String trimmed = line.trim();
                                        if (trimmed.startsWith("import ")) {
                                            String type = trimmed.substring(7).trim();
                                            if (type.startsWith("static ")) {
                                                type = type.substring(7).trim();
                                            }
                                            int semiIdx = type.indexOf(';');
                                            if (semiIdx != -1) {
                                                type = type.substring(0, semiIdx).trim();
                                            }
                                            types.add(type);
                                        }
                                    });
                        } catch (Exception ignored) {}
                    });
        }
        return types;
    }

    private static boolean isCommonRuntimeEssential(String groupId, String artifactId) {
        // Logging bridges and core framework essentials that might not be directly imported
        if (groupId.equals("org.springframework") && artifactId.equals("spring-jcl")) return true;
        if (groupId.equals("commons-logging") && artifactId.equals("commons-logging")) return true;
        if (groupId.equals("org.slf4j") && artifactId.startsWith("jcl-over-slf4j")) return true;
        if (groupId.equals("org.yaml") && artifactId.equals("snakeyaml")) return true;

        // Embedded Web Servers (Tomcat, Jetty, Undertow, Netty)
        if (groupId.startsWith("org.apache.tomcat")) return true;
        if (groupId.startsWith("org.eclipse.jetty")) return true;
        if (groupId.startsWith("io.undertow")) return true;
        if (groupId.startsWith("io.netty")) return true;
        if (groupId.startsWith("io.projectreactor.netty")) return true;

        return false;
    }

    public static void applyExclusions(Path projectPath, Collection<DepReport> reports, boolean commentOnly) {
        Path pomPath = projectPath.resolve("pom.xml");
        ExecutionContext ctx = new InMemoryExecutionContext();

        try {
            MavenParser mavenParser = MavenParser.builder().build();
            List<SourceFile> currentPomList = mavenParser.parse(Collections.singletonList(pomPath), projectPath, ctx)
                    .collect(Collectors.toList());

            for (DepReport report : reports) {
                if (report.isDirect && commentOnly) {
                    currentPomList = currentPomList.stream()
                            .map(sourceFile -> (SourceFile) new CommentOutTagVisitor("dependency", report.groupId, report.artifactId).visit(sourceFile, ctx))
                            .collect(Collectors.toList());
                    continue;
                }

                org.openrewrite.Recipe recipe;
                if (report.isDirect) {
                    recipe = new RemoveDependency(report.groupId, report.artifactId, null);
                } else {
                    recipe = new ExcludeDependency(report.groupId, report.artifactId, null);
                }

                org.openrewrite.LargeSourceSet sourceSet = new org.openrewrite.internal.InMemoryLargeSourceSet(currentPomList);
                org.openrewrite.RecipeRun recipeRun = recipe.run(sourceSet, ctx);

                List<org.openrewrite.Result> results = recipeRun.getChangeset().getAllResults();

                if (!results.isEmpty()) {
                    currentPomList = currentPomList.stream()
                            .map(sourceFile -> {
                                for (org.openrewrite.Result result : results) {
                                    if (sourceFile.getId().equals(result.getBefore().getId())) {
                                        return result.getAfter();
                                    }
                                }
                                return sourceFile;
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                }

                if (!report.isDirect && commentOnly) {
                    // After adding the exclusion, comment it out
                    currentPomList = currentPomList.stream()
                            .map(sourceFile -> (SourceFile) new CommentOutTagVisitor("exclusion", report.groupId, report.artifactId).visit(sourceFile, ctx))
                            .collect(Collectors.toList());
                }
            }

            if (!currentPomList.isEmpty() && currentPomList.get(0) != null) {
                Files.writeString(pomPath, currentPomList.get(0).printAll());
            }

        } catch (Exception e) {
            System.err.println("⚠️ Warning: Failed to apply modifications via OpenRewrite: " + e.getMessage());
        }
    }

    private static class CommentOutTagVisitor extends XmlVisitor<ExecutionContext> {
        private final String tagName;
        private final String groupId;
        private final String artifactId;

        public CommentOutTagVisitor(String tagName, String groupId, String artifactId) {
            this.tagName = tagName;
            this.groupId = groupId;
            this.artifactId = artifactId;
        }

        @Override
        public Xml visitTag(Xml.Tag tag, ExecutionContext ctx) {
            Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
            if (tagName.equals(t.getName()) && isMatchingDependency(t, groupId, artifactId)) {
                String commentText = t.print(getCursor()).trim();
                return new Xml.Comment(
                        org.openrewrite.Tree.randomId(),
                        t.getPrefix(),
                        t.getMarkers(),
                        "\n" + commentText + "\n"
                );
            }
            return t;
        }

        private boolean isMatchingDependency(Xml.Tag tag, String groupId, String artifactId) {
            return groupId.equals(tag.getChildValue("groupId").orElse("")) &&
                   artifactId.equals(tag.getChildValue("artifactId").orElse(""));
        }
    }
}