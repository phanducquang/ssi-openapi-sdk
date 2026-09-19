package io.github.phanducquang.ssi.trading;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSignerTest {
    @Test
    void signsBase64EncodedXmlRsaKeyWithSha256Pkcs1() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        var privateKey = (RSAPrivateKey) keyPair.getPrivate();
        var publicKey = (RSAPublicKey) keyPair.getPublic();

        String xml = "<RSAKeyValue>"
                + "<Modulus>" + Base64.getEncoder().encodeToString(unsigned(privateKey.getModulus())) + "</Modulus>"
                + "<D>" + Base64.getEncoder().encodeToString(unsigned(privateKey.getPrivateExponent())) + "</D>"
                + "</RSAKeyValue>";
        String encodedXml = Base64.getEncoder()
                .encodeToString(xml.getBytes(StandardCharsets.UTF_8));

        String body = "{"accountNo":"1234567","symbol":"VNM"}";
        String signatureHex = RequestSigner.sign(body, encodedXml);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(body.getBytes(StandardCharsets.UTF_8));

        assertTrue(verifier.verify(HexFormat.of().parseHex(signatureHex)));
    }

    @Test
    void generatesNanoIdStyleRequestId() {
        String id = RequestIdGenerator.generate();

        assertEquals(20, id.length());
        assertTrue(id.chars().allMatch(ch -> RequestIdGenerator.ALPHABET.indexOf(ch) >= 0));
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }
}
