//usr/bin/env jbang --ea "$0" "$@" ; exit $?
//JAVA 21+
//MAIN TestCspuRelease
//SOURCES ../mandrel-ops.java

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.kohsuke.github.GHCommit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Local release-classification regression tests; no GitHub access or signing key required. */
class TestCspuRelease {
    private static final String VERSION_PATCH = """
            @@ -1,4 +1,4 @@
             suite = {
            -  "version" : "25.0.5",
            -  "release" : False,
            +  "version" : "25.0.5.1",
            +  "release" : True,
             }
            """;
    private static final String ASM_PATCH = """
            @@ -4 +4 @@
            -  "ASM_9.7.1" : {
            +  "ASM_9.8" : {
            """;

    public static void main(String[] args) throws Exception {
        check("No changed files", true);
        final GHCommit.File version = file("sdk/mx.sdk/suite.py", VERSION_PATCH, 2, 2);
        check("Version/release-only changes", true, version);
        check("Several benign suite files", true, version,
                file("compiler/mx.compiler/suite.py", VERSION_PATCH, 2, 2));
        check("ASM dependency update in suite.py", false,
                file("sdk/mx.sdk/suite.py", ASM_PATCH, 1, 1));
        check("Version bump and dependency update in the same suite", false,
                file("sdk/mx.sdk/suite.py", VERSION_PATCH + ASM_PATCH, 3, 3));
        check("Dependency update in another suite", false, version,
                file("compiler/mx.compiler/suite.py", ASM_PATCH, 1, 1));
        check("Changes outside suite.py", false, version,
                file("compiler/src/Fix.java", VERSION_PATCH, 2, 2));
        check("Missing patch", false, file("sdk/mx.sdk/suite.py", null, 1, 1));
        check("Blank patch", false, file("sdk/mx.sdk/suite.py", "", 1, 1));
        check("Truncated patch", false,
                file("sdk/mx.sdk/suite.py", VERSION_PATCH, 3, 3));
        check("Unavailable comparison files", false, (GHCommit.File[]) null);
        verifyLocalDetector();
    }

    private static GHCommit.File file(String name, String patch, int additions, int deletions) {
        return new GHCommit.File() {
            @Override public String getFileName() { return name; }
            @Override public String getPatch() { return patch; }
            @Override public int getLinesAdded() { return additions; }
            @Override public int getLinesDeleted() { return deletions; }
        };
    }

    private static void check(String scenario, boolean expected, GHCommit.File... files) {
        if (PublishRelease.isEmptyCSPU(files) != expected) {
            throw new AssertionError(scenario + ": expected empty CSPU = " + expected);
        }
        System.out.println("[OK] " + scenario);
    }

    private static void verifyLocalDetector() throws Exception {
        final Path repoDir = Files.createTempDirectory("mandrel-cspu-release-");
        try {
            try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
                final Path suite = repoDir.resolve("sdk/mx.sdk/suite.py");
                Files.createDirectories(suite.getParent());
                final String initial = "suite = {\n  \"version\" : \"25.0.5\",\n  \"release\" : True,\n  \"ASM_9.7.1\" : {},\n}\n";
                Files.writeString(suite, initial);
                final RevCommit base = commit(git);
                Files.writeString(suite, initial.replace("25.0.5", "25.0.5.1"));
                final RevCommit version = commit(git);
                if (!UpstreamMark.walkTheDiff(git, base, version)) {
                    throw new AssertionError("Local detector rejected version-only changes");
                }
                Files.writeString(suite, Files.readString(suite).replace("ASM_9.7.1", "ASM_9.8"));
                if (UpstreamMark.walkTheDiff(git, base, commit(git))) {
                    throw new AssertionError("Local detector accepted a dependency update");
                }
                System.out.println("[OK] Local Git detector shares the release publisher's content checks");
            }
        } finally {
            try (Stream<Path> paths = Files.walk(repoDir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static RevCommit commit(Git git) throws Exception {
        final PersonIdent identity = new PersonIdent("Test", "test@example.org");
        git.add().addFilepattern(".").call();
        return git.commit().setAuthor(identity).setCommitter(identity).setSign(false)
                .setMessage("Update suite").call();
    }
}
