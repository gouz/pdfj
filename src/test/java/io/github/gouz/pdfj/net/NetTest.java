package io.github.gouz.pdfj.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetTest {
    @Test
    void imagePolicy() throws IOException {
        Path root = Files.createTempDirectory("pdfj-policy");
        Path site = Files.createDirectories(root.resolve("site"));
        Files.write(site.resolve("logo.png"), new byte[] {'x'});
        Files.write(root.resolve("secret.png"), new byte[] {'x'});
        try {
            Files.createSymbolicLink(site.resolve("link.png"), root.resolve("secret.png"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // no symlinks here
        }
        ImagePolicy p = new ImagePolicy(site, null);
        ImagePolicy none = ImagePolicy.NONE;
        assertTrue(Images.resolve("data:image/png;base64,AA==", none) != null);
        assertNull(Images.resolve("logo.png", none)); // no base dir: no local files
        assertTrue(Images.resolve("logo.png", p) != null);
        assertTrue(Images.resolve("./logo.png?v=2", p) != null);
        assertNull(Images.resolve("../secret.png", p)); // ../ escape
        assertNull(Images.resolve(root.resolve("secret.png").toString(), p)); // absolute path outside
        assertNull(Images.resolve("link.png", p)); // symlink escape
        assertNull(Images.resolve("missing.png", p));
        assertNull(Images.resolve("file:///etc/hosts", p));
        assertNull(Images.resolve("https://x.test/a.png", p));
        assertNull(Images.resolve(".", p));
    }

    @Test
    void publicAddresses() throws IOException {
        for (String priv : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "0.0.0.0", "100.64.0.1", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1")) {
            assertFalse(Remote.isPublic(InetAddress.getByName(priv)), priv);
        }
        for (String pub : List.of("93.184.216.34", "1.1.1.1", "2606:4700:4700::1111")) {
            assertTrue(Remote.isPublic(InetAddress.getByName(pub)), pub);
        }
    }

    @Test
    void hosts() {
        Remote.Config c = Remote.Config.defaults().withHosts(List.of("cdn.example.com", "*.img.test"));
        assertTrue(c.hostAllowed("cdn.example.com") && c.hostAllowed("a.img.test") && c.hostAllowed("img.test"));
        assertTrue(!c.hostAllowed("example.com") && !c.hostAllowed("evilimg.test"));
        assertTrue(Remote.Config.defaults().hostAllowed("anything.test"));
        assertEquals("Host.test", Remote.hostOf("https://user@Host.test:8080/a?b"));
        assertEquals("::1", Remote.hostOf("http://[::1]:80/"));
    }

    @Test
    void refusedWithoutNetwork() {
        Remote.Config c = Remote.Config.defaults();
        assertNull(Remote.fetch("ftp://x.test/a", c));
        assertNull(Remote.fetch("http://127.0.0.1:9/a.png", c)); // loopback refused before connecting
        assertNull(Remote.fetch("https://bad.test/a.png", Remote.Config.defaults().withHosts(List.of("good.test"))));
    }
}
