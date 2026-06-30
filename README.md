# Spring Prune CLI

`spring-prune` is a safe, framework-aware command-line utility designed to optimize Spring Boot Maven codebases by analyzing and removing unused dependencies.

Unlike generic dependency analyzers that rely solely on static class imports, `spring-prune` blends static analysis with configuration parsing and automatic compilation safety checks to eliminate dead-weight libraries without breaking runtime behaviors.

---

## 🚀 Key Features

*   **Static Import Analysis:** Utilizes OpenRewrite to parse source code files and identify top-level `pom.xml` dependencies that lack active `import` paths.
*   **Transitive Dependency Pruning:** Traces dependency lineage to identify bloat introduced by third-party libraries and automatically applies `<exclusion>` tags.
*   **Multi-Module Support:** Recursively discovers and processes all modules within a Maven project tree, ensuring consistency across complex architectures.
*   **Spring Configuration Awareness:** Automatically parses `application.properties` and `application.yml` files to prevent the accidental deletion of database drivers and core infrastructure modules.
*   **Annotation Intelligence:** Scans Java sources for Spring, Jakarta, and Lombok annotations (e.g., `@RestController`, `@Entity`, `@Data`) to protect runtime-essential libraries.
*   **Developer Comment Overrides:** Supports inline XML comments (`<!-- spring-prune:keep -->`) inside the `pom.xml` so developers can explicitly whitelist individual dependencies.
*   **Lossless Refactoring:** Modifies the target `pom.xml` using OpenRewrite's Lossless Semantic Tree (LST) architecture, preserving your original XML indentation, formatting, and custom comments perfectly.
*   **Safe-Mode Rollback:** Executes an automatic background verification step (`mvn test-compile`). If the build breaks due to a deleted runtime dependency, the tool instantly rolls back the `pom.xml` to its original state.

---

## 📂 Architecture & Execution Lifecycle

The CLI operates in five sequential phases to ensure maximum precision and zero-downtime safety:

### Developer Overrides (pom.xml)
To prevent the tool from removing a specific dependency that doesn't have direct code imports (like a background documentation generator or a runtime web asset bundle), place the <!-- spring-prune:keep --> flag inside or directly above the <dependency> element:

```XML
<dependency>
<groupId>org.springdoc</groupId>
<artifactId>springdoc-openapi-ui</artifactId>
<!-- spring-prune:keep (Generates documentation at runtime) -->
</dependency>
```

### CLI Command Options
```Bash
Usage: spring-prune [-cdhV] [-s=<settingsPath>] -p=<projectPath>
```
Safely cleans up unused dependencies in Spring Boot applications.
```
Options:
  -p, --path=<projectPath>   Target Spring Boot project root directory.
  -d, --dry-run              Scan and print results without modifying pom.xml.
  -c, --comment              Comment out unused dependencies instead of removing them.
  -s, --settings=<settings>  Optional path to a custom Maven settings.xml file.
  -h, --help                 Show this help message and exit.
  -V, --version              Print version information and exit.
```

### Example Commands
Run a dry-run analysis to preview bloatware:

```Bash
java -jar spring-prune-cli.jar --path /Users/dev/my-spring-app --dry-run
```

Comment out unused dependencies instead of removing them:

```Bash
java -jar spring-prune-cli.jar --path /Users/dev/my-spring-app --comment
```

Use a custom Maven settings file for private repositories:

```Bash
java -jar spring-prune-cli.jar --path /Users/dev/my-spring-app --settings ~/.m2/my-company-settings.xml
```
### 🛠️ Build and Installation
1. Clone or navigate to the CLI tool codebase.

2. Package the application into an executable single fat JAR:

```Bash
mvn clean package
```

Locate the generated executable at target/spring-prune-cli-1.0-SNAPSHOT.jar.