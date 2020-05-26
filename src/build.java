import java.io.File;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class build
{
    static final Logger logger = LogManager.getLogger(build.class);

    public static void main(String... args)
    {
        final var options = Options.from(Args.read(args));
        final var localPaths = LocalPaths.newSystemPaths(options);
        final var build = new Build(localPaths, options);

        logger.info("Build the bits!");
        SequentialBuild.build(build);
    }
}

class Build
{
    final LocalPaths paths;
    final Options options;

    Build(LocalPaths paths, Options options)
    {
        this.paths = paths;
        this.options = options;
    }
}

class Dependency
{
    final String id;
    final String version;
    final String sha1;
    final String sourceSha1;
    final Pattern pattern;

    Dependency(String id, String version, String sha1, String sourceSha1, Pattern pattern)
    {
        this.id = id;
        this.version = version;
        this.sha1 = sha1;
        this.sourceSha1 = sourceSha1;
        this.pattern = pattern;
    }

    static Dependency of(Map<String, String> fields)
    {
        final var id = fields.get("id");
        final var version = fields.get("version");
        final var sha1 = fields.get("sha1");
        final var sourceSha1 = fields.get("sourceSha1");
        final var pattern = Pattern.compile(String.format("%s[^({|\\n)]*\\{", id));
        return new Dependency(id, version, sha1, sourceSha1, pattern);
    }

    @Override
    public String toString()
    {
        return "Dependency{" +
            "id='" + id + '\'' +
            ", version='" + version + '\'' +
            ", sha1='" + sha1 + '\'' +
            ", sourceSha1='" + sourceSha1 + '\'' +
            ", pattern=" + pattern +
            '}';
    }
}

class Options
{
    final Action action;
    final String version;
    final boolean verbose;
    final String mavenProxy;
    final String mavenRepoId;
    final String mavenURL;
    final String mavenLocalRepository;
    final List<Dependency> dependencies;
    final boolean skipClean;

    Options(
        Action action
        , String version
        , boolean verbose
        , String mavenProxy
        , String mavenRepoId
        , String mavenURL
        , String mavenLocalRepository
        , List<Dependency> dependencies
        , boolean skipClean
    )
    {
        this.action = action;
        this.version = version;
        this.verbose = verbose;
        this.mavenProxy = mavenProxy;
        this.mavenRepoId = mavenRepoId;
        this.mavenURL = mavenURL;
        this.mavenLocalRepository = mavenLocalRepository;
        this.dependencies = dependencies;
        this.skipClean = skipClean;
    }

    public static Options from(Map<String, List<String>> args)
    {
        final var action = args.containsKey("deploy")
            ? Action.DEPLOY
            : Action.INSTALL;
        final var version = required("version", args);
        final var verbose = args.containsKey("verbose");
        final var mavenProxy = optional("maven-proxy", args);
        final var mavenRepoId =
            requiredForDeploy("maven-repo-id", args, action);
        final var mavenURL =
            requiredForDeploy("maven-url", args, action);

        final var mavenLocalRepository =
            optional("maven-local-repository", args);

        final var dependenciesArg = args.get("dependencies");
        final var dependencies = dependenciesArg == null
            ? Collections.<Dependency>emptyList()
            : toDependencies(dependenciesArg);

        final var skipClean = args.containsKey("skipClean");

        return new Options(
            action
            , version
            , verbose
            , mavenProxy
            , mavenRepoId
            , mavenURL
            , mavenLocalRepository
            , dependencies
            , skipClean
        );
    }

    private static List<Dependency> toDependencies(List<String> args)
    {
        return args.stream()
            .map(Options::toFields)
            .map(Dependency::of)
            .collect(Collectors.toList());
    }

    static Map<String, String> toFields(String dependency)
    {
        final var fields = Arrays.asList(dependency.split(","));
        return fields.stream()
            .map(fs -> fs.split("="))
            .collect(Collectors.toMap(fs -> fs[0], fs -> fs[1]));
    }

    private static String requiredForDeploy(
        String name
        , Map<String, List<String>> args
        , Action action
    )
    {
        return action == Action.DEPLOY
            ? required(name, args)
            : optional(name, args);
    }

    static String snapshotVersion(Options options)
    {
        return String.format("%s-SNAPSHOT", options.version);
    }

    private static String optional(String name, Map<String, List<String>> args)
    {
        final var option = args.get(name);
        return option != null ? option.get(0) : null;
    }

    private static String required(String name, Map<String, List<String>> args)
    {
        final var option = args.get(name);
        if (Objects.isNull(option))
            throw new IllegalArgumentException(String.format(
                "Missing mandatory --%s"
                , name
            ));

        return option.get(0);
    }

    enum Action
    {
        INSTALL, DEPLOY
    }
}

class SequentialBuild
{
    static void build(Build build)
    {
        Mx.mx(build);
        Maven.mvn(build);
    }
}

class Mx
{
    private static final Logger LOG = LogManager.getLogger(OperatingSystem.class);

    private static final Pattern DEPENDENCY_SHA1_PATTERN =
        Pattern.compile("\"sha1\"\\s*:\\s*\"([a-f0-9]*)\"");
    private static final Pattern DEPENDENCY_SOURCE_SHA1_PATTERN =
        Pattern.compile("\"sourceSha1\"\\s*:\\s*\"([a-f0-9]*)\"");
    private static final Pattern DEPENDENCY_VERSION_PATTERN =
        Pattern.compile("\"version\"\\s*:\\s*\"([0-9.]*)\"");

    private static final String SVM_ONLY = String.join(","
        , "SVM"
        , "com.oracle.svm.graal"
        , "com.oracle.svm.truffle"
        , "com.oracle.svm.hosted"
        , "com.oracle.svm.truffle.nfi"
        , "com.oracle.svm.truffle.nfi.posix"
        , "com.oracle.svm.truffle.nfi.windows"
        , "com.oracle.svm.core.jdk11"
        , "com.oracle.svm.core"
        , "com.oracle.svm.core.posix"
        , "com.oracle.svm.core.windows"
        , "com.oracle.svm.core.genscavenge"
        , "com.oracle.svm.jni"
        , "com.oracle.svm.reflect"
        , "com.oracle.svm.util" // svm.core dependency
        // Explicit dependency to avoid pulling libffi
        , "TRUFFLE_NFI"
        , "com.oracle.truffle.nfi"
        , "com.oracle.truffle.nfi.spi"
    );

    static final Map<String, Stream<BuildArgs>> BUILD_STEPS = Map.of(
        "sdk", Stream.of(BuildArgs.empty())
        , "substratevm", Stream.of(
            BuildArgs.of("--dependencies", "GRAAL_SDK")
            , BuildArgs.of("--dependencies", "GRAAL")
            , BuildArgs.of("--dependencies", "POINTSTO")
            , BuildArgs.of("--dependencies", "OBJECTFILE")
            , BuildArgs.of("--dependencies", "SVM_DRIVER")
            , BuildArgs.of("--only", SVM_ONLY)
        )
    );

    static void mx(Build build)
    {
        Mx.build(build).accept("substratevm");
    }

    private static Consumer<String> build(Build build)
    {
        return artifactName ->
        {
            LOG.debugf("Build %s", artifactName);

            final var artifact = Mx.swapDependencies(build)
                .compose(Mx.hookMavenProxy(build))
                .compose(Mx.artifact(build))
                .apply(artifactName);

            final var clean = !build.options.skipClean;
            if (clean)
                OperatingSystem.exec(Mx.mxclean(artifact, build));

            artifact.buildSteps
                .map(Mx.mxbuild(artifact, build))
                .forEach(OperatingSystem::exec);
        };
    }

    private static OperatingSystem.Command mxclean(Artifact artifact, Build build)
    {
        return new OperatingSystem.Command(
            Stream.concat(
                Stream.of(
                    LocalPaths.mxRoot(build.paths).apply(Paths.get("mx")).toString()
                    , build.options.verbose ? "-V" : ""
                    , "clean"
                )
                , Stream.empty()
            )
            , artifact.rootPath
            , Stream.empty()
        );
    }

    private static Function<BuildArgs, OperatingSystem.Command> mxbuild(Artifact artifact, Build build)
    {
        return buildArgs ->
            new OperatingSystem.Command(
                Stream.concat(
                    Stream.of(
                        LocalPaths.mxRoot(build.paths).apply(Paths.get("mx")).toString()
                        , build.options.verbose ? "-V" : ""
                        , "--trust-http"
                        , "build"
                        , "--no-native"
                    )
                    , buildArgs.args
                )
                , artifact.rootPath
                , Stream.empty()
            );
    }

    private static Function<String, Artifact> artifact(Build build)
    {
        return artifactName ->
        {
            final var fromGraalHome = LocalPaths.graalHome(build.paths);

            var rootPath = fromGraalHome
                .apply(Path.of(artifactName));

            var buildArgs = BUILD_STEPS.get(artifactName);
            return new Artifact(rootPath, buildArgs);
        };
    }

    static Function<Artifact, Artifact> swapDependencies(Build build)
    {
        return artifact ->
        {
            final var dependencies = build.options.dependencies;
            if (dependencies.isEmpty())
                return artifact;

            LOG.debugf("Swap dependencies: %s", dependencies);
            try
            {
                final var suitePy = Path.of("mx.mx", "suite.py");
                final var path = LocalPaths.mxRoot(build.paths).apply(suitePy);

                try (var lines = Files.lines(path))
                {
                    ParsedDependencies parsed = parseSuitePy(dependencies, lines);
                    final var transformed = applyDependencies(dependencies, parsed);
                    Files.write(path, transformed);
                }

                return artifact;
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        };
    }

    static List<String> applyDependencies(List<Dependency> dependencies, ParsedDependencies parsed)
    {
        final var result = new ArrayList<>(parsed.lines);
        dependencies.forEach(apply(a -> a.version, parsed.versions, result));
        dependencies.forEach(apply(a -> a.sha1, parsed.sha1s, result));
        dependencies.forEach(apply(a -> a.sourceSha1, parsed.sourceSha1s, result));
        return result;
    }

    static Consumer<Dependency> apply(
        Function<Dependency, String> extract
        , Map<String, Coordinate> values
        , List<String> lines
    )
    {
        return artifact ->
        {
            final var coordinate = values.get(artifact.id);
            if (coordinate != null)
            {
                final var line = lines.get(coordinate.lineNumber);
                final var replaced = line.replace(coordinate.value, extract.apply(artifact));
                lines.set(coordinate.lineNumber, replaced);
            }
        };
    }

    static ParsedDependencies parseSuitePy(List<Dependency> dependencies, Stream<String> lines)
    {
        int lineNumber = -1;
        String id = null;
        String tmp;

        final var output = new ArrayList<String>();
        final Map<String, Coordinate> versions = new HashMap<>();
        final Map<String, Coordinate> sha1s = new HashMap<>();
        final Map<String, Coordinate> sourceSha1s = new HashMap<>();

        final var it = lines.iterator();
        while (it.hasNext())
        {
            final var line = it.next();

            lineNumber++;
            output.add(line);

            if (id == null)
            {
                final var maybeArtifact = dependencies.stream()
                    .filter(artifact -> artifact.pattern.matcher(line).find())
                    .findFirst();

                if (maybeArtifact.isPresent())
                {
                    id = maybeArtifact.get().id;
                }
            }
            else
            {
                tmp = extract(line, DEPENDENCY_SHA1_PATTERN);
                if (tmp != null)
                {
                    sha1s.put(id, new Coordinate(tmp, lineNumber));
                    continue;
                }

                tmp = extract(line, DEPENDENCY_SOURCE_SHA1_PATTERN);
                if (tmp != null)
                {
                    sourceSha1s.put(id, new Coordinate(tmp, lineNumber));
                    continue;
                }

                tmp = extract(line, DEPENDENCY_VERSION_PATTERN);
                if (tmp != null)
                {
                    versions.put(id, new Coordinate(tmp, lineNumber));
                    id = null;
                }
            }
        }

        return new ParsedDependencies(output, versions, sha1s, sourceSha1s);
    }

    static String extract(String line, Pattern pattern)
    {
        final var matcher = pattern.matcher(line);
        if (matcher.find())
        {
            return matcher.group(1);
        }
        return null;
    }

    static Function<Artifact, Artifact> hookMavenProxy(Build build)
    {
        return artifact ->
        {
            if (build.options.mavenProxy != null)
            {
                Mx.prependMavenProxyToMxPy(build.options)
                    .compose(Mx::backupOrRestoreMxPy)
                    .apply(build);
            }

            return artifact;
        };
    }

    private static Function<Path, Void> prependMavenProxyToMxPy(Options options)
    {
        return mxPy ->
        {
            try (var lines = OperatingSystem.readLines(mxPy))
            {
                final var replaced = lines
                    .filter(Mx::notMavenOrg)
                    .map(prependMavenProxy(options))
                    .collect(Collectors.toList());
                OperatingSystem.writeLines(mxPy, replaced);
                return null;
            }
        };
    }

    private static boolean notMavenOrg(String line)
    {
        return !line.contains("maven.org");
    }

    private static Path backupOrRestoreMxPy(Build build)
    {
        final var mxHome = LocalPaths.mxRoot(build.paths);
        Path backupMxPy = mxHome.apply(Paths.get("mx.py.backup"));
        Path mxPy = mxHome.apply(Paths.get("mx.py"));
        if (!backupMxPy.toFile().exists())
        {
            OperatingSystem.copy(mxPy, backupMxPy);
        }
        else
        {
            OperatingSystem.copy(backupMxPy, mxPy, REPLACE_EXISTING);
        }

        return mxPy;
    }

    private static Function<String, String> prependMavenProxy(Options options)
    {
        return line ->
        {
            var mavenBaseURL = String.format("\"%s/\"", options.mavenProxy);
            return line.contains("_mavenRepoBaseURLs")
                ? line.replaceFirst("\\[", String.format("[ %s", mavenBaseURL))
                : line;
        };
    }

    static class BuildArgs
    {
        final Stream<String> args;

        private BuildArgs(Stream<String> args)
        {
            this.args = args;
        }

        static BuildArgs of(String... args)
        {
            return new BuildArgs(Stream.of(args));
        }

        static BuildArgs empty()
        {
            return new BuildArgs(Stream.empty());
        }
    }

    private static class Artifact
    {
        final Path rootPath;
        final Stream<BuildArgs> buildSteps;

        Artifact(
            Path rootPath
            , Stream<BuildArgs> buildSteps
        )
        {
            this.rootPath = rootPath;
            this.buildSteps = buildSteps;
        }
    }

    static final class ParsedDependencies
    {
        final List<String> lines;
        final Map<String, Coordinate> versions;
        final Map<String, Coordinate> sha1s;
        final Map<String, Coordinate> sourceSha1s;

        ParsedDependencies(
            List<String> lines
            , Map<String, Coordinate> versions
            , Map<String, Coordinate> sha1s
            , Map<String, Coordinate> sourceSha1s
        )
        {
            this.lines = lines;
            this.versions = versions;
            this.sha1s = sha1s;
            this.sourceSha1s = sourceSha1s;
        }
    }

    static final class Coordinate
    {
        final String value;
        final int lineNumber;

        Coordinate(String value, int lineNumber)
        {
            this.value = value;
            this.lineNumber = lineNumber;
        }

        @Override
        public String toString()
        {
            return "Coordinate{" +
                "value='" + value + '\'' +
                ", lineNumber=" + lineNumber +
                '}';
        }
    }
}

class LocalPaths
{
    final Path graalHome;
    final Path mxHome;
    final Path workingDir;
    final Path mavenRepoHome;

    private LocalPaths(Path graalHome, Path mxHome, Path workingDir, Path mavenRepoHome)
    {
        this.graalHome = graalHome;
        this.mxHome = mxHome;
        this.workingDir = workingDir;
        this.mavenRepoHome = mavenRepoHome;
    }

    static Function<Path, Path> graalHome(LocalPaths paths)
    {
        return paths.graalHome::resolve;
    }

    static Function<Path, Path> mxRoot(LocalPaths paths)
    {
        return paths.mxHome::resolve;
    }

    static Function<Path, Path> targetDir(LocalPaths paths)
    {
        return paths.workingDir.resolve("target")::resolve;
    }

    static Function<Path, Path> resourcesDir(LocalPaths paths)
    {
        return paths.workingDir.resolve("resources")::resolve;
    }

    static LocalPaths newSystemPaths(Options options)
    {
        final var graalHome = Path.of("/tmp", "mandrel");
        final var mxHome = Path.of("/opt", "mx");
        final var userDir = System.getProperty("user.dir");
        final var workingDir = new File(userDir).toPath();
        final var mavenRepoHome = mavenRepoHome(options);
        return new LocalPaths(graalHome, mxHome, workingDir, mavenRepoHome);
    }

    private static Path mavenRepoHome(Options options)
    {
        if (options.mavenLocalRepository == null)
        {
            final var userHome = System.getProperty("user.home");
            return Path.of(userHome, ".m2", "repository");
        }

        return Path.of(options.mavenLocalRepository);
    }
}

class Maven
{
    static final Logger LOG = LogManager.getLogger(Maven.class);

    static final Collection<String> ARTIFACT_IDS = Arrays.asList(
        "graal-sdk"
        , "svm"
        , "pointsto"
        , "truffle-api"
        , "compiler"
        , "objectfile"
        , "svm-driver"
    );

    static final String INSTALL_FILE_VERSION = "2.4";

    static final String INSTALL_FILE_GOAL = String.format(
        "org.apache.maven.plugins:maven-install-plugin:%s:install-file"
        , INSTALL_FILE_VERSION
    );

    static final String DEPLOY_FILE_VERSION = "2.7";

    static final String DEPLOY_FILE_GOAL = String.format(
        "org.apache.maven.plugins:maven-deploy-plugin:%s:deploy-file"
        , DEPLOY_FILE_VERSION
    );

    static final Map<String, String> GROUP_IDS = Map.of(
        "graal-sdk", "org.graalvm.sdk"
        , "svm", "org.graalvm.nativeimage"
        , "pointsto", "org.graalvm.nativeimage"
        , "truffle-api", "org.graalvm.truffle"
        , "compiler", "org.graalvm.compiler"
        , "objectfile", "org.graalvm.nativeimage"
        , "svm-driver", "org.graalvm.nativeimage"
    );

    static final Map<String, Path> DISTS_PATHS = Map.of(
        "graal-sdk", Path.of("sdk", "mxbuild", "dists", "jdk11", "graal-sdk")
        , "svm", Path.of("substratevm", "mxbuild", "dists", "jdk11", "svm")
        , "pointsto", Path.of("substratevm", "mxbuild", "dists", "jdk11", "pointsto")
        , "truffle-api", Path.of("truffle", "mxbuild", "dists", "jdk11", "truffle-api")
        , "compiler", Path.of("compiler", "mxbuild", "dists", "jdk11", "graal")
        , "objectfile", Path.of("substratevm", "mxbuild", "dists", "jdk1.8", "objectfile")
        , "svm-driver", Path.of("substratevm", "mxbuild", "dists", "jdk1.8", "svm-driver")
    );

    static void mvn(Build build)
    {
        // Only invoke mvn if all mx builds succeeded
        final var releaseArtifacts =
            ARTIFACT_IDS.stream()
                .map(Maven.mvnInstall(build))
                .collect(Collectors.toList());

        // Only deploy if all mvn installs worked
        if (build.options.action == Options.Action.DEPLOY)
        {
            releaseArtifacts.forEach(Maven.mvnDeploy(build));
        }
    }

    private static Function<String, ReleaseArtifact> mvnInstall(Build build)
    {
        return artifactId ->
        {
            final var artifact =
                Maven.artifact(artifactId);

            Maven.mvnInstallSnapshot(artifact, build);

            Maven.mvnInstallAssembly(build)
                .compose(AssemblyArtifact.of(build))
                .apply(artifact);

            return Maven.mvnInstallRelease(build)
                .compose(ReleaseArtifact.of(build))
                .apply(artifact);
        };
    }

    private static Consumer<ReleaseArtifact> mvnDeploy(Build build)
    {
        return artifact ->
            OperatingSystem.exec()
                .compose(Maven.deploy(build))
                .apply(artifact);
    }

    private static Function<ReleaseArtifact, OperatingSystem.Command> deploy(Build build)
    {
        return artifact ->
            new OperatingSystem.Command(
                Stream.of(
                    "mvn"
                    , build.options.verbose ? "--debug" : ""
                    , DEPLOY_FILE_GOAL
                    , String.format("-DgroupId=%s", artifact.groupId)
                    , String.format("-DartifactId=%s", artifact.artifactId)
                    , String.format("-Dversion=%s", build.options.version)
                    , "-Dpackaging=jar"
                    , String.format("-Dfile=%s", artifact.jarPath)
                    , String.format("-Dsources=%s", artifact.sourceJarPath)
                    , "-DcreateChecksum=true"
                    , String.format("-DpomFile=%s", artifact.pomPaths.target)
                    , String.format("-DrepositoryId=%s", build.options.mavenRepoId)
                    , String.format("-Durl=%s", build.options.mavenURL)
                )
                , build.paths.workingDir
                , Stream.empty()
            );
    }

    private static Artifact artifact(String artifactId)
    {
        final var distsPath = DISTS_PATHS.get(artifactId);
        final var groupId = GROUP_IDS.get(artifactId);
        return new Artifact(
            groupId
            , artifactId
            , distsPath
        );
    }

    private static Function<DirectionalPaths, DirectionalPaths> preparePomXml(Build build)
    {
        return paths ->
        {
            LOG.debugf("Create parent directories for %s", paths.target);
            OperatingSystem.mkdirs()
                .compose(Path::getParent)
                .apply(paths.target);

            OperatingSystem.copy(paths.source, paths.target, REPLACE_EXISTING);

            try (var lines = OperatingSystem.readLines(paths.target))
            {
                final var replaced = lines
                    .map(line -> line.replace("999", build.options.version))
                    .collect(Collectors.toList());
                OperatingSystem.writeLines(paths.target, replaced);
            }

            return paths;
        };
    }

    private static void mvnInstallSnapshot(Artifact artifact, Build build)
    {
        OperatingSystem.exec()
            .compose(Maven.mvnInstallSnapshot(build))
            .apply(artifact);
    }

    private static Function<Artifact, OperatingSystem.Command> mvnInstallSnapshot(Build build)
    {
        return artifact ->
            new OperatingSystem.Command(
                Stream.of(
                    "mvn"
                    , build.options.verbose ? "--debug" : ""
                    , INSTALL_FILE_GOAL
                    , String.format("-DgroupId=%s", artifact.groupId)
                    , String.format("-DartifactId=%s", artifact.artifactId)
                    , String.format("-Dversion=%s", Options.snapshotVersion(build.options))
                    , "-Dpackaging=jar"
                    , String.format(
                        "-Dfile=%s.jar"
                        , artifact.distsPath.toString()
                    )
                    , String.format(
                        "-Dsources=%s.src.zip"
                        , artifact.distsPath.toString()
                    )
                    , "-DcreateChecksum=true"
                )
                , build.paths.graalHome
                , Stream.empty()
            );
    }

    private static Function<AssemblyArtifact, Void> mvnInstallAssembly(Build build)
    {
        return artifact ->
            OperatingSystem.exec()
                .compose(Maven.installAssembly(build))
                .compose(Maven.prepareAssemblyPomXml(build))
                .apply(artifact);
    }

    private static Function<AssemblyArtifact, AssemblyArtifact> prepareAssemblyPomXml(Build build)
    {
        return artifact ->
        {
            Maven.preparePomXml(build).apply(artifact.pomPaths);
            return artifact;
        };
    }

    private static Function<AssemblyArtifact, OperatingSystem.Command> installAssembly(Build build)
    {
        return artifact ->
            new OperatingSystem.Command(
                Stream.of(
                    "mvn"
                    , build.options.verbose ? "--debug" : ""
                    , "install"
                )
                , artifact.pomPaths.target.getParent()
                , Stream.empty()
            );
    }

    private static Function<ReleaseArtifact, ReleaseArtifact> mvnInstallRelease(Build build)
    {
        return artifact ->
        {
            OperatingSystem.exec()
                .compose(Maven.installRelease(build))
                .compose(Maven.prepareReleasePomXml(build))
                .apply(artifact);
            return artifact;
        };
    }

    private static Function<ReleaseArtifact, ReleaseArtifact> prepareReleasePomXml(Build build)
    {
        return artifact ->
        {
            Maven.preparePomXml(build).apply(artifact.pomPaths);
            return artifact;
        };
    }

    private static Function<ReleaseArtifact, OperatingSystem.Command> installRelease(Build build)
    {
        return artifact ->
            new OperatingSystem.Command(
                Stream.of(
                    "mvn"
                    , build.options.verbose ? "--debug" : ""
                    , INSTALL_FILE_GOAL
                    , String.format("-DgroupId=%s", artifact.groupId)
                    , String.format("-DartifactId=%s", artifact.artifactId)
                    , String.format("-Dversion=%s", build.options.version)
                    , "-Dpackaging=jar"
                    , String.format("-Dfile=%s", artifact.jarPath)
                    , String.format("-Dsources=%s", artifact.sourceJarPath)
                    , "-DcreateChecksum=true"
                    , String.format("-DpomFile=%s", artifact.pomPaths.target)
                )
                , build.paths.workingDir
                , Stream.empty()
            );
    }

    private static final class Artifact
    {
        final String groupId;
        final String artifactId;
        final Path distsPath;

        Artifact(String groupId, String artifactId, Path distsPath)
        {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.distsPath = distsPath;
        }
    }

    private static final class AssemblyArtifact
    {
        final DirectionalPaths pomPaths;

        private AssemblyArtifact(DirectionalPaths pomPaths)
        {
            this.pomPaths = pomPaths;
        }

        static Function<Artifact, AssemblyArtifact> of(Build build)
        {
            return artifact ->
            {
                final var pomPath = Path.of(
                    "assembly"
                    , artifact.artifactId
                    , "pom.xml"
                );
                final var pomPaths = new DirectionalPaths(
                    LocalPaths.resourcesDir(build.paths).apply(pomPath)
                    , LocalPaths.targetDir(build.paths).apply(pomPath)
                );

                return new AssemblyArtifact(pomPaths);
            };
        }
    }

    private static final class ReleaseArtifact
    {
        final String groupId;
        final String artifactId;
        final DirectionalPaths pomPaths;
        final Path jarPath;
        final Path sourceJarPath;

        private ReleaseArtifact(
            String groupId
            , String artifactId
            , DirectionalPaths pomPaths
            , Path jarPath
            , Path sourceJarPath
        )
        {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.pomPaths = pomPaths;
            this.jarPath = jarPath;
            this.sourceJarPath = sourceJarPath;
        }

        static Function<Artifact, ReleaseArtifact> of(Build build)
        {
            return artifact ->
            {
                final var releasePomPath = Path.of(
                    "release"
                    , artifact.artifactId
                    , "pom.xml"
                );

                final var pomPaths = new DirectionalPaths(
                    LocalPaths.resourcesDir(build.paths).apply(releasePomPath)
                    , LocalPaths.targetDir(build.paths).apply(releasePomPath)
                );

                final var jarName = String.format(
                    "%s-%s-ASSEMBLY-jar-with-dependencies.jar"
                    , artifact.artifactId
                    , build.options.version
                );

                final var artifactPath = build.paths.mavenRepoHome
                    .resolve(artifact.groupId.replace(".", "/"))
                    .resolve(artifact.artifactId);

                final var jarPath = artifactPath
                    .resolve(String.format("%s-ASSEMBLY", build.options.version))
                    .resolve(jarName);

                final var sourceJarName = String.format(
                    "%s-%s-SNAPSHOT-sources.jar"
                    , artifact.artifactId
                    , build.options.version
                );

                final var sourceJarPath = artifactPath
                    .resolve(String.format("%s-SNAPSHOT", build.options.version))
                    .resolve(sourceJarName);

                return new ReleaseArtifact(
                    artifact.groupId
                    , artifact.artifactId
                    , pomPaths
                    , jarPath
                    , sourceJarPath
                );
            };
        }
    }

    private static final class DirectionalPaths
    {
        final Path source;
        final Path target;

        private DirectionalPaths(Path source, Path target)
        {
            this.source = source;
            this.target = target;
        }
    }
}

class OperatingSystem
{
    static final Logger LOG = LogManager.getLogger(OperatingSystem.class);

    static Function<OperatingSystem.Command, Void> exec()
    {
        return command ->
        {
            exec(command);
            return null;
        };
    }

    static void exec(Command command)
    {
        final var commandList = command.command
            .filter(Predicate.not(String::isEmpty))
            .collect(Collectors.toList());

        LOG.debugf("Execute %s in %s", commandList, command.directory);
        try
        {
            var processBuilder = new ProcessBuilder(commandList)
                .directory(command.directory.toFile())
                .inheritIO();

            command.envVars.forEach(
                envVar -> processBuilder.environment()
                    .put(envVar.name, envVar.value)
            );

            Process process = processBuilder.start();

            if (process.waitFor() != 0)
            {
                throw new RuntimeException(
                    "Failed, exit code: " + process.exitValue()
                );
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    static void copy(Path from, Path to, CopyOption... copyOptions)
    {
        try
        {
            LOG.debugf("Copy %s to %s", from, to);
            Files.copy(from, to, copyOptions);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    static Function<Path, Path> mkdirs()
    {
        return OperatingSystem::mkdirs;
    }

    static Stream<String> readLines(Path path)
    {
        try
        {
            return Files.lines(path);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    static void writeLines(Path path, Iterable<? extends CharSequence> lines)
    {
        try
        {
            Files.write(path, lines);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static Path mkdirs(Path path)
    {
        final var file = path.toFile();
        if (!file.exists())
        {
            final var created = file.mkdirs();
            if (!created)
                throw new RuntimeException("Failed to create target directory");
        }
        return path;
    }

    static class Command
    {
        final Stream<String> command;
        final Path directory;
        final Stream<EnvVar> envVars;

        Command(Stream<String> command, Path directory, Stream<EnvVar> envVars)
        {
            this.command = command;
            this.directory = directory;
            this.envVars = envVars;
        }
    }

    static class EnvVar
    {
        final String name;
        final String value;

        EnvVar(String name, String value)
        {
            this.name = name;
            this.value = value;
        }
    }
}

final class Logger
{
    final String name;

    Logger(String name)
    {
        this.name = name;
    }

    void debugf(String format, Object... params)
    {
        System.out.printf("DEBUG [%s] %s%n"
            , name
            , String.format(format, params)
        );
    }

    public void info(String msg)
    {
        System.out.printf("INFO [%s] %s%n"
            , name
            , msg
        );
    }
}

final class Args
{
    static Map<String, List<String>> read(String... args)
    {
        final Map<String, List<String>> params = new HashMap<>();

        List<String> options = null;
        for (final String arg : args)
        {
            if (arg.startsWith("--"))
            {
                if (arg.length() < 3)
                {
                    System.err.println("Error at argument " + arg);
                    return params;
                }

                options = new ArrayList<>();
                params.put(arg.substring(2), options);
            }
            else if (options != null)
            {
                options.add(arg);
            }
            else
            {
                System.err.println("Illegal parameter usage");
                return params;
            }
        }

        return params;
    }
}

final class LogManager
{
    static Logger getLogger(Class<?> clazz)
    {
        return new Logger(clazz.getSimpleName());
    }
}
