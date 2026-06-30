package one.dastec.springprune.analyzer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpringConfigScanner {

    // Common DB URL patterns to map to their respective Maven artifacts
    private static final String POSTGRES_ARTIFACT = "org.postgresql:postgresql";
    private static final String MYSQL_ARTIFACT = "com.mysql:mysql-connector-j";
    private static final String H2_ARTIFACT = "com.h2database:h2";

    /**
     * Scans application configuration files to identify dependencies that must be kept.
     * @param projectPath The root path of the project.
     * @return A set of "groupId:artifactId" strings that shouldn't be pruned.
     */
    public static Set<String> generateSafeList(Path projectPath) {
        Set<String> safeList = new HashSet<>();
        Path resourcesPath = projectPath.resolve("src").resolve("main").resolve("resources");

        if (!Files.exists(resourcesPath) || !Files.isDirectory(resourcesPath)) {
            return safeList;
        }

        try {
            Files.walk(resourcesPath, 2)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        String fileName = file.getFileName().toString();
                        if (fileName.endsWith(".properties")) {
                            analyzePropertiesFile(file, safeList);
                        } else if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
                            analyzeYamlFile(file, safeList);
                        }
                    });
        } catch (IOException e) {
            String msg = e.getMessage();
            System.err.println("⚠️ Warning: Failed to scan resources directory: " + (msg != null ? msg : e.getClass().getName()));
        }

        return safeList;
    }

    private static void analyzePropertiesFile(Path filePath, Set<String> safeList) {
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            props.load(reader);

            String datasourceUrl = props.getProperty("spring.datasource.url", "");
            String r2dbcUrl = props.getProperty("spring.r2dbc.url", "");

            evaluateUrl(datasourceUrl, safeList);
            evaluateUrl(r2dbcUrl, safeList);
        } catch (IOException e) {
            String msg = e.getMessage();
            System.err.println("⚠️ Warning: Failed to read properties file " + filePath.getFileName() + ": " + (msg != null ? msg : e.getClass().getName()));
        }
    }

    private static void analyzeYamlFile(Path filePath, Set<String> safeList) {
        // Flat regex matchers to easily extract values from YAML without pulling in a heavy snakeyaml dependency
        Pattern urlPattern = Pattern.compile("url:\\s*['\"]?(jdbc|r2dbc):([^'\"]+)['\"]?");

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = urlPattern.matcher(line);
                if (matcher.find()) {
                    String fullUrl = matcher.group(0);
                    evaluateUrl(fullUrl, safeList);
                }
            }
        } catch (IOException e) {
            String msg = e.getMessage();
            System.err.println("⚠️ Warning: Failed to read YAML file " + filePath.getFileName() + ": " + (msg != null ? msg : e.getClass().getName()));
        }
    }

    private static void evaluateUrl(String url, Set<String> safeList) {
        if (url == null || url.isEmpty()) {
            return;
        }

        if (url.contains(":postgresql:")) {
            safeList.add(POSTGRES_ARTIFACT);
        } else if (url.contains(":mysql:")) {
            safeList.add(MYSQL_ARTIFACT);
        } else if (url.contains(":h2:")) {
            safeList.add(H2_ARTIFACT);
        }
    }
}