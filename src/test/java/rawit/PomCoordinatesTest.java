package rawit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates POM coordinates and metadata required for Maven Central deployment.
 *
 * Validates: Requirements 1.1, 1.2, 2.1, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6
 */
class PomCoordinatesTest {

    private static Document pom;

    @BeforeAll
    static void parsePom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        pom = builder.parse(new File("pom.xml"));
        pom.getDocumentElement().normalize();
    }

    private String topLevelText(String tagName) {
        NodeList nodes = pom.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el && el.getTagName().equals(tagName)) {
                return el.getTextContent().trim();
            }
        }
        return null;
    }

    private boolean topLevelElementExists(String tagName) {
        NodeList nodes = pom.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el && el.getTagName().equals(tagName)) {
                return true;
            }
        }
        return false;
    }

    // Validates: Requirement 1.1
    @Test
    void groupIdIsCorrect() {
        assertEquals("io.github.ronnygunawan", topLevelText("groupId"));
    }

    // Validates: Requirement 1.2
    @Test
    void artifactIdIsCorrect() {
        assertEquals("rawit", topLevelText("artifactId"));
    }

    // Validates: Requirement 2.1
    @Test
    void descriptionMentionsInvokerAndConstructor() {
        String description = topLevelText("description");
        assertNotNull(description, "description element must be present");
        assertTrue(description.contains("@Invoker"), "description must contain @Invoker");
        assertTrue(description.contains("@Constructor"), "description must contain @Constructor");
    }

    // Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5
    @Test
    void requiredTopLevelMetadataElementsArePresent() {
        assertTrue(topLevelElementExists("name"), "<name> must be present");
        assertTrue(topLevelElementExists("url"), "<url> must be present");
        assertTrue(topLevelElementExists("licenses"), "<licenses> must be present");
        assertTrue(topLevelElementExists("developers"), "<developers> must be present");
        assertTrue(topLevelElementExists("scm"), "<scm> must be present");
    }

    // Validates: Requirement 3.6
    @Test
    void releaseProfileContainsRequiredPlugins() {
        NodeList profiles = pom.getElementsByTagName("profile");
        Element releaseProfile = null;
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            NodeList ids = profile.getElementsByTagName("id");
            if (ids.getLength() > 0 && "release".equals(ids.item(0).getTextContent().trim())) {
                releaseProfile = profile;
                break;
            }
        }
        assertNotNull(releaseProfile, "release profile must exist");

        String profileXml = releaseProfile.getTextContent();
        // Check via artifactId elements within the release profile
        NodeList artifactIds = releaseProfile.getElementsByTagName("artifactId");
        boolean hasSourcePlugin = false;
        boolean hasJavadocPlugin = false;
        boolean hasGpgPlugin = false;
        boolean hasCentralPlugin = false;
        for (int i = 0; i < artifactIds.getLength(); i++) {
            String id = artifactIds.item(i).getTextContent().trim();
            if ("maven-source-plugin".equals(id)) hasSourcePlugin = true;
            if ("maven-javadoc-plugin".equals(id)) hasJavadocPlugin = true;
            if ("maven-gpg-plugin".equals(id)) hasGpgPlugin = true;
            if ("central-publishing-maven-plugin".equals(id)) hasCentralPlugin = true;
        }
        assertTrue(hasSourcePlugin, "release profile must contain maven-source-plugin");
        assertTrue(hasJavadocPlugin, "release profile must contain maven-javadoc-plugin");
        assertTrue(hasGpgPlugin, "release profile must contain maven-gpg-plugin");
        assertTrue(hasCentralPlugin, "release profile must contain central-publishing-maven-plugin");
    }

    // Validates: Requirement 3.6
    @Test
    void autoPublishTrueImpliesWaitUntilPublished() {
        NodeList profiles = pom.getElementsByTagName("profile");
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            NodeList ids = profile.getElementsByTagName("id");
            if (ids.getLength() > 0 && "release".equals(ids.item(0).getTextContent().trim())) {
                NodeList autoPublishNodes = profile.getElementsByTagName("autoPublish");
                for (int j = 0; j < autoPublishNodes.getLength(); j++) {
                    String autoPublish = autoPublishNodes.item(j).getTextContent().trim();
                    if ("true".equals(autoPublish)) {
                        // Find sibling waitUntil in the same configuration element
                        Element configEl = (Element) autoPublishNodes.item(j).getParentNode();
                        NodeList waitUntilNodes = configEl.getElementsByTagName("waitUntil");
                        assertTrue(waitUntilNodes.getLength() > 0,
                                "waitUntil must be present when autoPublish=true");
                        assertEquals("published", waitUntilNodes.item(0).getTextContent().trim(),
                                "waitUntil must be 'published' when autoPublish=true");
                    }
                }
            }
        }
    }
}
