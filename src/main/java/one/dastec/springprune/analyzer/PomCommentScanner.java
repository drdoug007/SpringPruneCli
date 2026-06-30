package one.dastec.springprune.analyzer;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class PomCommentScanner {

    private static final String IGNORE_TOKEN = "spring-prune:keep";

    /**
     * Scans the pom.xml for dependencies that have the explicit <!-- spring-prune:keep --> comment.
     * @param projectPath The root path of the project containing the pom.xml
     * @return A set of strings in the format "groupId:artifactId" to be kept.
     */
    public static Set<String> findExplicitlyKeptDependencies(Path projectPath) {
        Set<String> keptDependencies = new HashSet<>();
        File pomFile = projectPath.resolve("pom.xml").toFile();

        try {
            // Configure DocumentBuilder to ignore whitespace but PRESERVE comments
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringElementContentWhitespace(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile);

            doc.getDocumentElement().normalize();

            // Get all <dependency> tags
            NodeList dependencyNodes = doc.getElementsByTagName("dependency");

            for (int i = 0; i < dependencyNodes.getLength(); i++) {
                Node depNode = dependencyNodes.item(i);

                if (depNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element depElement = (Element) depNode;

                    // Extract coordinates
                    String groupId = getTagValue("groupId", depElement);
                    String artifactId = getTagValue("artifactId", depElement);

                    if (groupId != null && artifactId != null) {
                        String dependencyId = groupId + ":" + artifactId;

                        // Check if this dependency element contains or is preceded by our ignore token
                        if (hasKeepComment(depNode)) {
                            keptDependencies.add(dependencyId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            System.err.println("⚠️ Warning: Failed to parse pom.xml for comments: " + (msg != null ? msg : e.getClass().getName()));
        }

        return keptDependencies;
    }

    /**
     * Checks if a node contains the comment tag inside it or immediately around it.
     */
    private static boolean hasKeepComment(Node node) {
        // 1. Check internal comments (inside the <dependency>...</dependency> tags)
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.COMMENT_NODE) {
                if (child.getNodeValue().contains(IGNORE_TOKEN)) {
                    return true;
                }
            }
        }

        // 2. Check trailing/preceding comments (as sibling nodes)
        Node prevSibling = node.getPreviousSibling();
        while (prevSibling != null) {
            // Skip pure empty text nodes between tags
            if (prevSibling.getNodeType() == Node.COMMENT_NODE) {
                return prevSibling.getNodeValue().contains(IGNORE_TOKEN);
            } else if (prevSibling.getNodeType() == Node.ELEMENT_NODE) {
                break; // Hit another tag, stop looking back
            }
            prevSibling = prevSibling.getPreviousSibling();
        }

        return false;
    }

    private static String getTagValue(String tagName, Element element) {
        NodeList nodeList = element.getElementsByTagName(tagName);
        if (nodeList.getLength() > 0) {
            Node node = nodeList.item(0);
            if (node != null && node.hasChildNodes()) {
                return node.getFirstChild().getNodeValue().trim();
            }
        }
        return null;
    }
}