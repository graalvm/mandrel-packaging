import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class build
{
    static final Logger logger = LogManager.getLogger(build.class);

    public static void main(String... args)
    {
        Check.main();

        final var options = Options.from(Args.read(args));

        final var fs = FileSystem.ofSystem(options);
        final var os = new OperatingSystem();
        final var build = new SequentialBuild(fs, os);

        logger.info("Build the bits!");
        build.build(options);
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
    final String mandrelHome;
    final String mxHome;
    final List<Dependency> dependencies;
    final boolean skipClean;
    final boolean skipJava;
    final boolean skipNative;

    Options(
        Action action
        , String version
        , boolean verbose
        , String mavenProxy
        , String mavenRepoId
        , String mavenURL
        , String mavenLocalRepository
        , String mandrelHome
        , String mxHome
        , List<Dependency> dependencies
        , boolean skipClean
        , boolean skipJava
        , boolean skipNative
    )
    {
        this.action = action;
        this.version = version;
        this.verbose = verbose;
        this.mavenProxy = mavenProxy;
        this.mavenRepoId = mavenRepoId;
        this.mavenURL = mavenURL;
        this.mavenLocalRepository = mavenLocalRepository;
        this.mandrelHome = mandrelHome;
        this.mxHome = mxHome;
        this.dependencies = dependencies;
        this.skipClean = skipClean;
        this.skipJava = skipJava;
        this.skipNative = skipNative;
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
        final var mandrelHome =
            optional("mandrel-home", args);
        final var mxHome =
            optional("mx-home", args);

        final var dependenciesArg = args.get("dependencies");
        final var dependencies = dependenciesArg == null
            ? Collections.<Dependency>emptyList()
            : toDependencies(dependenciesArg);

        final var skipClean = args.containsKey("skipClean");
        final var skipJava = args.containsKey("skipJava");
        final var skipNative = args.containsKey("skipNative");

        return new Options(
            action
            , version
            , verbose
            , mavenProxy
            , mavenRepoId
            , mavenURL
            , mavenLocalRepository
            , mandrelHome
            , mxHome
            , dependencies
            , skipClean
            , skipJava
            , skipNative
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
    final FileSystem fs;
    final OperatingSystem os;

    SequentialBuild(FileSystem fs, OperatingSystem os) {
        this.fs = fs;
        this.os = os;
    }

    void build(Options options)
    {
        final var exec = new Tasks.Exec.Effects(os::exec);
        final var replace = Tasks.FileReplace.Effects.ofSystem();
        Mx.build(options, exec, replace, fs::mxHome, fs::mandrelHome, os::javaHome);
        if (!options.skipJava) {
            Maven.mvn(options, exec, replace, fs::mandrelHome, fs::workingDir, fs::mavenRepoHome);
        }
    }
}

class EnvVar
{
    final String name;
    final String value;

    EnvVar(String name, String value)
    {
        this.name = name;
        this.value = value;
    }
}

class Tasks
{
    static class Exec
    {
        final List<String> args;
        final Path directory;
        final List<EnvVar> envVars;

        private Exec(List<String> args, Path directory, List<EnvVar> envVars) {
            this.args = args;
            this.directory = directory;
            this.envVars = envVars;
        }

        static Exec of(List<String> args, Path directory, EnvVar... envVars)
        {
            final var nonEmpty = args.stream()
                .filter(Predicate.not(String::isEmpty))
                .collect(Collectors.toList());
            return new Tasks.Exec(nonEmpty, directory, Arrays.asList(envVars));
        }

        @Override
        public String toString()
        {
            return "Exec{" +
                "args=" + args +
                ", directory=" + directory +
                ", envVars=" + envVars +
                '}';
        }

        static class Effects
        {
            final Consumer<Exec> exec;

            Effects(Consumer<Exec> exec)
            {
                this.exec = exec;
            }
        }
    }

    static class FileReplace
    {
        private final Path path;
        private final Function<Stream<String>, List<String>> replacer;

        FileReplace(Path path, Function<Stream<String>, List<String>> replacer) {
            this.path = path;
            this.replacer = replacer;
        }

        static void replace(FileReplace replace, Effects effects)
        {
            try (var lines = effects.readLines.apply(replace.path))
            {
                final var transformed = replace.replacer.apply(lines);
                effects.writeLines.accept(replace.path, transformed);
            }
        }

        static void copyReplace(FileReplace replace, Path from, Effects effects)
        {
            effects.copy.accept(from, replace.path);
            FileReplace.replace(
                new FileReplace(replace.path, replace.replacer)
                , effects
            );
        }

        static class Effects
        {
            final Function<Path, Stream<String>> readLines;
            final BiConsumer<Path, List<String>> writeLines;
            final BiConsumer<Path, Path> copy;

            private Effects(
                Function<Path, Stream<String>> readLines
                , BiConsumer<Path, List<String>> writeLines
                , BiConsumer<Path, Path> copy
            )
            {
                this.readLines = readLines;
                this.writeLines = writeLines;
                this.copy = copy;
            }

            public static Effects ofSystem()
            {
                return new Effects(
                    FileSystem::readLines
                    , FileSystem::writeLines
                    , FileSystem::copy
                );
            }

            public static Effects noop()
            {
                return new Effects(p -> Stream.empty(), (p, l) -> {}, (src, target) -> {});
            }
        }
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

    static final String SVM_ONLY = String.join(","
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

    static final List<BuildArgs> BUILD_JAVA_STEPS = List.of(
            BuildArgs.of("--no-native", "--dependencies", "GRAAL_SDK,GRAAL,POINTSTO,OBJECTFILE,SVM_DRIVER")
            , BuildArgs.of("--no-native", "--only", SVM_ONLY)
    );

    static final List<BuildArgs> BUILD_NATIVE_STEPS = List.of(
            BuildArgs.of("--projects", "com.oracle.svm.native.libchelper,com.oracle.svm.native.jvm.posix")
            , BuildArgs.of("--only", "native-image.image-bash")
    );

    static void build(
        Options options
        , Tasks.Exec.Effects exec
        , Tasks.FileReplace.Effects replace
        , Function<Path, Path> mxHome
        , Function<Path, Path> mandrelHome
        , Supplier<Path> javaHome
    )
    {
        Mx.swapDependencies(options, replace, mxHome);
        Mx.hookMavenProxy(options, replace, mxHome);

        final var clean = !options.skipClean;
        if (clean)
            exec.exec.accept(Mx.mxclean(options, mxHome, mandrelHome));

        if(!options.skipJava) {
            BUILD_JAVA_STEPS.stream()
                    .map(Mx.mxbuild(options, mxHome, mandrelHome, javaHome))
                    .forEach(exec.exec);
        }

        if(!options.skipNative) {
            BUILD_NATIVE_STEPS.stream()
                    .map(Mx.mxbuild(options, mxHome, mandrelHome, javaHome))
                    .forEach(exec.exec);
        }
    }

    private static Tasks.Exec mxclean(
        Options options
        , Function<Path, Path> mxHome
        , Function<Path, Path> mandrelHome
    )
    {
        final var mx = mxHome.apply(Paths.get("mx"));
        return Tasks.Exec.of(
            Arrays.asList(
                mx.toString()
                , options.verbose ? "-V" : ""
                , "clean"
            )
            , mandrelHome.apply(Path.of("substratevm"))
        );
    }

    private static Function<BuildArgs, Tasks.Exec> mxbuild(
        Options options
        , Function<Path, Path> mxHome
        , Function<Path, Path> mandrelHome
        , Supplier<Path> javaHome
    )
    {
        return buildArgs ->
        {
            final var mx = mxHome.apply(Paths.get("mx"));
            final var args = Lists.concat(
                List.of(
                    mx.toString()
                    , options.verbose ? "-V" : ""
                    , "--trust-http"
                    , "--java-home"
                    , javaHome.get().toString()
                    , "build"
                )
                , buildArgs.args
            );

            return Tasks.Exec.of(
                args
                , mandrelHome.apply(Path.of("substratevm"))
            );
        };
    }

    static void swapDependencies(Options options, Tasks.FileReplace.Effects effects, Function<Path, Path> mxHome)
    {
        final var dependencies = options.dependencies;
        if (dependencies.isEmpty())
            return;

        LOG.debugf("Swap dependencies: %s", dependencies);
        final var suitePy = Path.of("mx.mx", "suite.py");
        final var path = mxHome.apply(suitePy);

        Tasks.FileReplace.replace(
            new Tasks.FileReplace(path, swapDependencies(dependencies))
            , effects
        );
    }

    private static Function<Stream<String>, List<String>> swapDependencies(List<Dependency> dependencies)
    {
        return lines ->
        {
            ParsedDependencies parsed = parseSuitePy(dependencies, lines);
            return applyDependencies(dependencies, parsed);
        };
    }

    private static List<String> applyDependencies(List<Dependency> dependencies, ParsedDependencies parsed)
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

    static void hookMavenProxy(Options options, Tasks.FileReplace.Effects effects, Function<Path, Path> mxHome)
    {
        if (options.mavenProxy != null)
        {
            final var mxPy = mxHome.apply(Paths.get("mx.py"));
            Tasks.FileReplace.replace(
                new Tasks.FileReplace(mxPy, Mx.prependMavenProxyToMxPy(options))
                , effects
            );
        }
    }

    private static Function<Stream<String>, List<String>> prependMavenProxyToMxPy(Options options)
    {
        return lines ->
            lines
                .filter(Mx::notMavenOrg)
                .map(prependMavenProxy(options))
                .collect(Collectors.toList());
    }

    private static boolean notMavenOrg(String line)
    {
        return !line.contains("maven.org");
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
        final List<String> args;

        BuildArgs(List<String> args) {this.args = args;}

        static BuildArgs of(String... args)
        {
            return new BuildArgs(Arrays.asList(args));
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

class Maven
{
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

    static void mvn(
        Options options
        , Tasks.Exec.Effects exec
        , Tasks.FileReplace.Effects replace
        , Supplier<Path> mandrelHome
        , Function<Path, Path> workingDir
        , Function<Path, Path> mavenRepoHome
    )
    {
        // Only invoke mvn if all mx builds succeeded
        final var releaseArtifacts =
            ARTIFACT_IDS.stream()
                .map(Maven.mvnInstall(options, exec, replace, mandrelHome, workingDir, mavenRepoHome))
                .collect(Collectors.toList());

        // Only deploy if all mvn installs worked
        if (options.action == Options.Action.DEPLOY)
        {
            releaseArtifacts.forEach(Maven.mvnDeploy(options, exec, workingDir));
        }
    }

    private static Function<String, ReleaseArtifact> mvnInstall(
        Options options
        , Tasks.Exec.Effects exec
        , Tasks.FileReplace.Effects replace
        , Supplier<Path> mandrelHome
        , Function<Path, Path> workingDir
        , Function<Path, Path> mavenRepoHome
    )
    {
        return artifactId ->
        {
            final var artifact =
                Maven.artifact(artifactId);

            exec.exec.accept(mvnInstallSnapshot(artifact, options, mandrelHome));
            exec.exec.accept(mvnInstallAssembly(artifact, options, replace, workingDir));

            final var releaseArtifact = ReleaseArtifact.of(artifact, options, workingDir, mavenRepoHome);
            exec.exec.accept(mvnInstallRelease(releaseArtifact, options, replace, workingDir));

            return releaseArtifact;
        };
    }

    private static Consumer<ReleaseArtifact> mvnDeploy(Options options, Tasks.Exec.Effects exec, Function<Path, Path> workingDir)
    {
        return artifact ->
            exec.exec.accept(Maven.deploy(artifact, options, workingDir));
    }

    private static Tasks.Exec deploy(ReleaseArtifact artifact, Options options, Function<Path, Path> workingDir)
    {
         return Tasks.Exec.of(
                Arrays.asList(
                    "mvn"
                    , options.verbose ? "--debug" : ""
                    , DEPLOY_FILE_GOAL
                    , String.format("-DgroupId=%s", artifact.groupId)
                    , String.format("-DartifactId=%s", artifact.artifactId)
                    , String.format("-Dversion=%s", options.version)
                    , "-Dpackaging=jar"
                    , String.format("-Dfile=%s", artifact.jarPath)
                    , String.format("-Dsources=%s", artifact.sourceJarPath)
                    , "-DcreateChecksum=true"
                    , String.format("-DpomFile=%s", artifact.pomPaths.target)
                    , String.format("-DrepositoryId=%s", options.mavenRepoId)
                    , String.format("-Durl=%s", options.mavenURL)
                )
                , workingDir.apply(Path.of(""))
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

    private static Function<Stream<String>, List<String>> replacePomXml(Options options)
    {
        return lines ->
            lines
                .map(line -> line.replace("999", options.version))
                .collect(Collectors.toList());
    }

    private static Tasks.Exec mvnInstallSnapshot(Artifact artifact, Options options, Supplier<Path> mandrelHome)
    {
        return Tasks.Exec.of(
                Arrays.asList(
                    "mvn"
                    , options.verbose ? "--debug" : ""
                    , INSTALL_FILE_GOAL
                    , String.format("-DgroupId=%s", artifact.groupId)
                    , String.format("-DartifactId=%s", artifact.artifactId)
                    , String.format("-Dversion=%s", Options.snapshotVersion(options))
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
                , mandrelHome.get()
            );
    }

    private static Tasks.Exec mvnInstallAssembly(Artifact artifact, Options options, Tasks.FileReplace.Effects effects, Function<Path, Path> workingDir)
    {
        final var pomPath = Path.of(
            "assembly"
            , artifact.artifactId
            , "pom.xml"
        );
        final var paths = DirectionalPaths.ofPom(pomPath, workingDir);

        Tasks.FileReplace.copyReplace(
            new Tasks.FileReplace(paths.target, Maven.replacePomXml(options))
            , paths.source
            , effects
        );

        return Tasks.Exec.of(
            Arrays.asList(
                "mvn"
                , options.verbose ? "--debug" : ""
                , "install"
            )
            , paths.target.getParent()
        );
    }

    private static Tasks.Exec mvnInstallRelease(
        ReleaseArtifact releaseArtifact
        , Options options
        , Tasks.FileReplace.Effects replace
        , Function<Path, Path> workingDir
    )
    {
        Tasks.FileReplace.copyReplace(
            new Tasks.FileReplace(releaseArtifact.pomPaths.target, Maven.replacePomXml(options))
            , releaseArtifact.pomPaths.source
            , replace
        );

        return Tasks.Exec.of(
            Arrays.asList(
                "mvn"
                , options.verbose ? "--debug" : ""
                , INSTALL_FILE_GOAL
                , String.format("-DgroupId=%s", releaseArtifact.groupId)
                , String.format("-DartifactId=%s", releaseArtifact.artifactId)
                , String.format("-Dversion=%s", options.version)
                , "-Dpackaging=jar"
                , String.format("-Dfile=%s", releaseArtifact.jarPath)
                , String.format("-Dsources=%s", releaseArtifact.sourceJarPath)
                , "-DcreateChecksum=true"
                , String.format("-DpomFile=%s", releaseArtifact.pomPaths.target)
            )
            , workingDir.apply(Path.of(""))
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

        static ReleaseArtifact of(Artifact artifact, Options options, Function<Path, Path> workingDir, Function<Path, Path> mavenRepoHome)
        {
            final var releasePomPath = Path.of(
                "release"
                , artifact.artifactId
                , "pom.xml"
            );

            final var pomPaths = DirectionalPaths.ofPom(releasePomPath, workingDir);

            final var jarName = String.format(
                "%s-%s-ASSEMBLY-jar-with-dependencies.jar"
                , artifact.artifactId
                , options.version
            );

            final var artifactPath = mavenRepoHome
                .apply(Path.of(artifact.groupId.replace(".", "/")))
                .resolve(artifact.artifactId);

            final var jarPath = artifactPath
                .resolve(String.format("%s-ASSEMBLY", options.version))
                .resolve(jarName);

            final var sourceJarName = String.format(
                "%s-%s-SNAPSHOT-sources.jar"
                , artifact.artifactId
                , options.version
            );

            final var sourceJarPath = artifactPath
                .resolve(String.format("%s-SNAPSHOT", options.version))
                .resolve(sourceJarName);

            return new ReleaseArtifact(
                artifact.groupId
                , artifact.artifactId
                , pomPaths
                , jarPath
                , sourceJarPath
            );
        }
    }

    static final class DirectionalPaths
    {
        final Path source;
        final Path target;

        DirectionalPaths(Path source, Path target)
        {
            this.source = source;
            this.target = target;
        }

        static DirectionalPaths ofPom(Path pomPath, Function<Path, Path> workingDir)
        {
            return new DirectionalPaths(
                workingDir.apply(Path.of("resources").resolve(pomPath))
                , workingDir.apply(Path.of("target").resolve(pomPath))
            );
        }
    }
}

// Dependency
class FileSystem
{
    static final Logger LOG = LogManager.getLogger(FileSystem.class);

    private final Path mandrelHome;
    private final Path mxHome;
    private final Path workingDir;
    private final Path mavenRepoHome;

    FileSystem(Path mandrelHome, Path mxHome, Path workingDir, Path mavenRepoHome) {
        this.mandrelHome = mandrelHome;
        this.mxHome = mxHome;
        this.workingDir = workingDir;
        this.mavenRepoHome = mavenRepoHome;
    }

    Path mxHome(Path relative)
    {
        return mxHome.resolve(relative);
    }

    Path mandrelHome(Path relative)
    {
        return mandrelHome.resolve(relative);
    }

    Path mandrelHome()
    {
        return mandrelHome;
    }

    Path workingDir(Path relative)
    {
        return workingDir.resolve(relative);
    }

    Path mavenRepoHome(Path relative)
    {
        return mavenRepoHome.resolve(relative);
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

    static void copy(Path from, Path to)
    {
        try
        {
            LOG.debugf("Create parent directories for %s", to);
            FileSystem.mkdirs(to.getParent());

            LOG.debugf("Copy %s to %s", from, to);
            Files.copy(from, to, REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static void mkdirs(Path path)
    {
        final var file = path.toFile();
        if (!file.exists())
        {
            final var created = file.mkdirs();
            if (!created)
                throw new RuntimeException("Failed to create target directory");
        }
    }

    static FileSystem ofSystem(Options options)
    {
        final var mandrelHome = mandrelHome(options);
        final var mxHome = mxHome(options);
        final var userDir = System.getProperty("user.dir");
        final var workingDir = new File(userDir).toPath();
        final var mavenRepoHome = mavenRepoHome(options);
        return new FileSystem(mandrelHome, mxHome, workingDir, mavenRepoHome);
    }

    private static Path mandrelHome(Options options)
    {
        if (options.mandrelHome == null)
        {
            return Path.of("/tmp", "mandrel");
        }

        return Path.of(options.mandrelHome);
    }

    private static Path mxHome(Options options)
    {
        if (options.mxHome == null)
        {
            return Path.of("/opt", "mx");
        }

        return Path.of(options.mxHome);
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

class OperatingSystem
{
    static final Logger LOG = LogManager.getLogger(OperatingSystem.class);

    void exec(Tasks.Exec task)
    {
        LOG.debugf("Execute %s in %s", task.args, task.directory);
        try
        {
            var processBuilder = new ProcessBuilder(task.args)
                .directory(task.directory.toFile())
                .inheritIO();

            task.envVars.forEach(
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

    Path javaHome()
    {
        return Path.of(System.getProperty("java.home"));
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

final class Lists
{
    static <E> List<E> concat(List<? extends E> a, List<? extends E> b)
    {
        final var list = new ArrayList<E>(a.size() + b.size());
        list.addAll(a);
        list.addAll(b);
        return list;
    }
}

final class Check
{
    public static void main(String... args)
    {
        shouldEnableAssertions();
        checkMx();
    }

    static void checkMx()
    {
        final var options = Options.from(Args.read("--version", "0.19.1"));
        final var os = new RecordingOperatingSystem();
        final var exec = new Tasks.Exec.Effects(os::record);
        final var replace = Tasks.FileReplace.Effects.noop();
        final Function<Path, Path> identity = Function.identity();
        final Supplier<Path> javaHome = () -> Path.of("java");
        Mx.build(options, exec, replace, identity, identity, javaHome);
        os.assertNumberOfTasks(5);
        os.assertTask("mx clean");
        os.assertTask("mx --trust-http --java-home java build --no-native --dependencies GRAAL_SDK,GRAAL,POINTSTO,OBJECTFILE,SVM_DRIVER");
        os.assertTask(String.format(
            "mx --trust-http --java-home java build --no-native --only %s"
            , Mx.SVM_ONLY
        ));
        os.assertTask("mx --trust-http --java-home java build --projects com.oracle.svm.native.libchelper,com.oracle.svm.native.jvm.posix");
        os.assertTask("mx --trust-http --java-home java build --only native-image.image-bash");
    }

    private static void shouldEnableAssertions()
    {
        boolean enabled = false;
        //noinspection AssertWithSideEffects
        assert enabled = true;
        //noinspection ConstantConditions
        if (!enabled)
            throw new AssertionError("assert not enabled");
    }

    // TODO use a marker or equivalent as return of exec and verify that instead of tracking tasks separately
    private static final class RecordingOperatingSystem
    {
        private final Queue<Tasks.Exec> tasks = new ArrayDeque<>();

        void record(Tasks.Exec task)
        {
            final var success = tasks.offer(task);
            assert success : task;
        }

        void assertNumberOfTasks(int size)
        {
            assert tasks.size() == size
                : String.format("%d:%s", tasks.size(), tasks.toString());
        }

        private void assertTask(String expected)
        {
            assertTask(t ->
            {
                final var actual = String.join(" ", t.args);
                assert actual.equals(expected) : t;
            });
        }

        private void assertTask(Consumer<Tasks.Exec> asserts)
        {
            final Tasks.Exec head = peekTask();
            asserts.accept(head);
            forward();
        }

        private Tasks.Exec peekTask()
        {
            return tasks.peek();
        }

        private void forward()
        {
            tasks.remove();
        }
    }
}
