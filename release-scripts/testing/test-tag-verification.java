//usr/bin/env jbang --ea "$0" "$@" ; exit $?
//JAVA 21+
//MAIN TestTagVerification
//SOURCES ../mandrel-ops.java

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Local regression tests; no GitHub access or signing key is required. */
class TestTagVerification {
    public static void main(String[] args) throws Exception {
        final Path repoDir = Files.createTempDirectory("mandrel-tag-verification-");
        try {
            try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
                final PersonIdent identity = new PersonIdent("Test", "test@example.org");
                final RevCommit taggedCommit = git.commit().setAllowEmpty(true)
                        .setAuthor(identity).setCommitter(identity).setSign(false)
                        .setMessage("Release commit").call();
                // Signed tags have the same tag-object indirection as this unsigned annotated tag.
                git.tag().setName("annotated").setAnnotated(true).setSigned(false)
                        .setTagger(identity).setMessage("Release tag").call();
                git.tag().setName("lightweight").setAnnotated(false).setSigned(false).call();

                UpstreamFinalize.verifyExistingTag(git, "annotated");
                UpstreamFinalize.verifyExistingTag(git, "lightweight");
                System.out.println("[OK] Annotated and lightweight tags targeting HEAD are accepted.");

                final RevCommit head = git.commit().setAllowEmpty(true)
                        .setAuthor(identity).setCommitter(identity).setSign(false)
                        .setMessage("Next commit").call();
                for (String tag : new String[] { "annotated", "lightweight" }) {
                    try {
                        UpstreamFinalize.verifyExistingTag(git, tag);
                        throw new AssertionError("Tag targeting an older commit was accepted: " + tag);
                    } catch (RuntimeException expected) {
                        if (!expected.getMessage().contains(taggedCommit.getName()) ||
                                !expected.getMessage().contains(head.getName())) {
                            throw new AssertionError("Error must identify the target commit and HEAD", expected);
                        }
                    }
                }
                System.out.println("[OK] Annotated and lightweight tags targeting another commit are rejected.");
            }
        } finally {
            try (Stream<Path> paths = Files.walk(repoDir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
