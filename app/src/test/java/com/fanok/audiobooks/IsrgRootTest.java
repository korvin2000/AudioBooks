package com.fanok.audiobooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.FileInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * SEC-06: the bundled trust anchors must be the genuine, self-signed ISRG Root X1 and X2 (checked by fingerprint).
 * X2 is the root of Let's Encrypt's ECDSA chains (YE1/YE2 via "Root YE": bookinfo.net, akniga.me), absent from the
 * system store before Android 14.
 */
public class IsrgRootTest {

    private static final String ISRG_ROOT_X1_SHA256 =
            "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6";
    private static final String ISRG_ROOT_X2_SHA256 =
            "69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470";

    @Test
    public void bundledAnchorIsIsrgRootX1() throws Exception {
        assertAnchor("src/main/res/raw/isrg_root_x1.txt", ISRG_ROOT_X1_SHA256, "ISRG Root X1");
    }

    @Test
    public void bundledAnchorIsIsrgRootX2() throws Exception {
        assertAnchor("src/main/res/raw/isrg_root_x2.txt", ISRG_ROOT_X2_SHA256, "ISRG Root X2");
    }

    private static void assertAnchor(String path, String sha256, String name) throws Exception {
        X509Certificate cert;
        try (FileInputStream in = new FileInputStream(path)) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02X", b));
        }
        assertEquals(sha256, hex.toString());
        assertEquals("CN=" + name + ", O=Internet Security Research Group, C=US",
                cert.getSubjectX500Principal().getName().replace(",", ", "));
        assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
    }
}
