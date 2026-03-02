package com.example.releve_bancaire.accounting_services;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

@Component
public class WinsOsXmlParser {

    private static final List<String> COMPTE_TAGS = List.of("compte", "ncompte", "compte_principal", "account");
    private static final List<String> CONTREPARTIE_TAGS = List.of(
            "compte_contrepartie", "contrepartie", "counterpart_account", "compteContrepartie");
    private static final List<String> JOURNAL_TAGS = List.of("journal", "ndosjrn", "journal_code");
    private static final List<String> USER_TAGS = List.of("user", "username", "db_user", "login");
    private static final List<String> PASSWORD_TAGS = List.of("password", "db_password", "pwd", "pass");

    public ParsedWinsOsXml parse(InputStream inputStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(inputStream);
            Element root = document.getDocumentElement();

            String comptePrincipal = firstNonBlank(root, COMPTE_TAGS);
            String compteContrepartie = firstNonBlank(root, CONTREPARTIE_TAGS);
            String journal = firstNonBlank(root, JOURNAL_TAGS);
            String xmlDbUser = firstNonBlank(root, USER_TAGS);
            String xmlDbPassword = firstNonBlank(root, PASSWORD_TAGS);

            if (comptePrincipal == null || comptePrincipal.isBlank()) {
                throw new IllegalArgumentException("XML invalide: compte principal introuvable.");
            }
            if (journal == null || journal.isBlank()) {
                throw new IllegalArgumentException("XML invalide: journal introuvable.");
            }

            return new ParsedWinsOsXml(
                    sanitize(comptePrincipal),
                    sanitize(compteContrepartie),
                    sanitize(journal),
                    sanitize(xmlDbUser),
                    sanitize(xmlDbPassword));
        } catch (Exception e) {
            throw new IllegalArgumentException("Impossible de parser wins_os.xml: " + e.getMessage(), e);
        }
    }

    private String firstNonBlank(Element root, List<String> tags) {
        for (String tag : tags) {
            var nodes = root.getElementsByTagName(tag);
            if (nodes.getLength() > 0) {
                String value = nodes.item(0).getTextContent();
                if (value != null && !value.trim().isBlank()) {
                    return value.trim();
                }
            }
            // fallback case-insensitive
            var allNodes = root.getElementsByTagName("*");
            for (int i = 0; i < allNodes.getLength(); i++) {
                String nodeName = allNodes.item(i).getNodeName();
                if (nodeName != null && nodeName.toLowerCase(Locale.ROOT).equals(tag.toLowerCase(Locale.ROOT))) {
                    String value = allNodes.item(i).getTextContent();
                    if (value != null && !value.trim().isBlank()) {
                        return value.trim();
                    }
                }
            }
        }
        return null;
    }

    private String sanitize(String value) {
        return value == null ? null : value.trim();
    }

    public record ParsedWinsOsXml(
            String comptePrincipal,
            String compteContrepartie,
            String journal,
            String xmlDbUser,
            String xmlDbPassword) {
    }
}

