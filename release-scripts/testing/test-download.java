//usr/bin/env jbang --ea "$0" "$@" ; exit $?
//JAVA 21+
//MAIN TestDownload
//SOURCES ../mandrel-ops.java

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Tests downloading a checksum into a directory that does not exist yet. */
class TestDownload {
    public static void main(String[] args) throws Exception {
        final String filename = "graalvm-community-jdk-25i6-25.0.5.1_linux-aarch64_bin.tar.gz.sha256";
        final String checksum = "a".repeat(64) + "\n";
        final byte[] body = checksum.getBytes(StandardCharsets.UTF_8);
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final Path tempDir = Files.createTempDirectory("mandrel-download-");
        final Path artifacts = tempDir.resolve("artifacts");
        final Path expected = artifacts.resolve(filename);
        try {
            server.createContext("/" + filename, exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + filename;
            final Path downloaded = SuiteOpsUtils.downloadIfNotExists(url, artifacts.toFile()).toPath();
            if (!expected.equals(downloaded) || !checksum.equals(Files.readString(downloaded))) {
                throw new AssertionError("Checksum was not downloaded to " + expected);
            }
            System.out.println("[OK] Download into missing artifacts directory");
        } finally {
            server.stop(0);
            Files.deleteIfExists(expected);
            Files.deleteIfExists(artifacts);
            Files.deleteIfExists(tempDir);
        }
    }
}
