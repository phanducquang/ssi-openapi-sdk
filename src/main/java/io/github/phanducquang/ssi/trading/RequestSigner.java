package io.github.phanducquang.ssi.trading;

import io.github.phanducquang.ssi.exception.SsiApiException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPrivateKeySpec;
import java.util.Base64;
import java.util.HexFormat;

public final class RequestSigner {
    private RequestSigner() {
    }

    public static String sign(String data, String privateKey) {
        if (data == null) {
            throw new IllegalArgumentException("data is required");
        }
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalArgumentException("SSI privateKey is required for signed trading requests");
        }

        try {
            RsaParts parts = parsePrivateKey(privateKey);
            var keySpec = new RSAPrivateKeySpec(parts.modulus(), parts.privateExponent());
            var key = KeyFactory.getInstance("RSA").generatePrivate(keySpec);

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signature.sign());
        } catch (Exception e) {
            throw new SsiApiException("Failed to sign SSI trading request", e);
        }
    }

    private static RsaParts parsePrivateKey(String privateKey) throws Exception {
        byte[] xmlBytes = Base64.getDecoder().decode(privateKey);
        String xml = new String(xmlBytes, StandardCharsets.UTF_8);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));

        BigInteger modulus = readBigInteger(document, "Modulus");
        BigInteger privateExponent = readBigInteger(document, "D");
        return new RsaParts(modulus, privateExponent);
    }

    private static BigInteger readBigInteger(Document document, String tag) {
        Node node = document.getElementsByTagName(tag).item(0);
        if (node == null || node.getTextContent() == null || node.getTextContent().isBlank()) {
            throw new IllegalArgumentException("SSI RSA private key is missing XML element: " + tag);
        }
        return new BigInteger(1, Base64.getDecoder().decode(node.getTextContent().trim()));
    }

    private record RsaParts(BigInteger modulus, BigInteger privateExponent) {
    }
}
