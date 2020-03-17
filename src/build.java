import java.io.File;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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

class Options
{
    private static final List<String> DEFAULT_ARTIFACTS =
        Arrays.asList("sdk", "substratevm");

    final Action action;
    final String version;
    final boolean verbose;
    final String mavenProxy;
    final String mavenRepoId;
    final String mavenURL;
    final List<String> artifactNames;
    final String mavenLocalRepository;

    Options(
        Action action
        , String version
        , boolean verbose
        , String mavenProxy
        , String mavenRepoId
        , String mavenURL
        , List<String> artifactNames
        , String mavenLocalRepository
    )
    {
        this.action = action;
        this.version = version;
        this.verbose = verbose;
        this.mavenProxy = mavenProxy;
        this.mavenRepoId = mavenRepoId;
        this.mavenURL = mavenURL;
        this.artifactNames = artifactNames;
        this.mavenLocalRepository = mavenLocalRepository;
    }

    public static Options from(Map<String, List<String>> args)
    {
        final var action = args.containsKey("deploy")
            ? Action.DEPLOY
            : Action.INSTALL;
        final var version = required("version", args);
        final var verbose = args.containsKey("verbose");
        final var mavenProxy = optional("maven-proxy", args);
        final var mavenRepoId = action == Action.DEPLOY
            ? required("maven-repo-id", args)
            : optional("maven-repo-id", args);
        final var mavenURL = action == Action.DEPLOY
            ? required("maven-repo-id", args)
            : optional("maven-url", args);
        final var artifactsArg = args.get("artifacts");
        final var artifacts = artifactsArg == null
            ? DEFAULT_ARTIFACTS
            : artifactsArg;
        final var mavenLocalRepository =
            optional("maven-local-repository", args);

        return new Options(
            action
            , version
            , verbose
            , mavenProxy
            , mavenRepoId
            , mavenURL
            , artifacts
            , mavenLocalRepository);
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
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"([0-9]\\.[0-9]{1,3}\\.[0-9]{1,2})\"");

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
        , "com.oracle.svm.core.graal.amd64"
        , "com.oracle.svm.core.graal.aarch64"
        , "com.oracle.svm.core.posix.jdk11"
        , "com.oracle.svm.core.posix"
        , "com.oracle.svm.core.windows"
        , "com.oracle.svm.core.genscavenge"
        , "com.oracle.svm.jni"
        , "com.oracle.svm.reflect"
        , "com.oracle.svm.util" // svm.core dependency
        , "com.oracle.svm.core.graal" // svm.hosted dependency
        // Explicit dependency to avoid pulling libffi
        , "TRUFFLE_NFI"
        , "com.oracle.truffle.nfi"
        , "com.oracle.truffle.nfi.spi"
    );

    static final Map<String, Stream<BuildArgs>> BUILD_STEPS = Map.of(
        "sdk", Stream.of(BuildArgs.empty())
        , "substratevm", Stream.of(
            BuildArgs.of("--dependencies", "GRAAL")
            , BuildArgs.of("--dependencies", "POINTSTO")
            , BuildArgs.of("--dependencies", "OBJECTFILE")
            , BuildArgs.of("--only", SVM_ONLY)
        )
    );

    static void mx(Build build)
    {
        build.options.artifactNames.forEach(Mx.build(build));
    }

    private static Consumer<String> build(Build build)
    {
        return artifactName ->
        {
            LOG.debugf("Build %s", artifactName);

            final var artifact = Mx.hookMavenProxy(build.options)
                .compose(Mx.artifact(build))
                .apply(artifactName);

            artifact.buildSteps
                .map(Mx.mxbuild(artifact, build.options))
                .forEach(OperatingSystem::exec);
        };
    }

    private static Function<BuildArgs, OperatingSystem.Command> mxbuild(Artifact artifact, Options options)
    {
        return buildArgs ->
            new OperatingSystem.Command(
                Stream.concat(
                    Stream.of(
                        artifact.mxHome.resolve("mx").toString()
                        , options.verbose ? "-V" : ""
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

            var mxHome = LocalPaths.mxRoot(build.paths)
                .compose(Mx::mxVersion)
                .compose(fromGraalHome)
                .compose(Mx::suitePy)
                .apply(artifactName);
            var buildArgs = BUILD_STEPS.get(artifactName);
            return new Artifact(rootPath, mxHome, buildArgs);
        };
    }

    private static Path suitePy(String artifactName)
    {
        return Path.of(
            artifactName
            , String.format("mx.%s", artifactName)
            , "suite.py"
        );
    }

    private static String mxVersion(Path suitePy)
    {
        try (var lines = OperatingSystem.readLines(suitePy))
        {
            return lines
                .filter(line -> line.contains("mxversion"))
                .map(Mx::extractMxVersion)
                .findFirst()
                .orElse(null);
        }
    }

    private static String extractMxVersion(String line)
    {
        final var matcher = VERSION_PATTERN.matcher(line);
        return matcher.results()
            .findFirst()
            .map(result -> result.group(1))
            .orElse(null);
    }

    static Function<Artifact, Artifact> hookMavenProxy(Options options)
    {
        return artifact ->
        {
            if (options.mavenProxy != null)
            {
                Mx.prependMavenProxyToMxPy(options)
                    .compose(Mx::backupOrRestoreMxPy)
                    .apply(artifact);
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

    private static Path backupOrRestoreMxPy(Artifact artifact)
    {
        Path backupMxPy = artifact.mxHome.resolve("mx.py.backup");
        Path mxPy = artifact.mxHome.resolve("mx.py");
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
        final Path mxHome;
        final Stream<BuildArgs> buildSteps;

        Artifact(
            Path rootPath
            , Path mxHome
            , Stream<BuildArgs> buildSteps
        )
        {
            this.rootPath = rootPath;
            this.mxHome = mxHome;
            this.buildSteps = buildSteps;
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

    static Function<String, Path> mxRoot(LocalPaths paths)
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
        if (options.mavenLocalRepository == null) {
            final var userHome = System.getProperty("user.home");
            return Path.of(userHome, ".m2", "repository");
        }

        return Path.of(options.mavenLocalRepository);
    }
}

class Maven
{
    static final Logger LOG = LogManager.getLogger(Maven.class);

    static final Map<String, String> GROUP_IDS = Map.of(
        "sdk", "org.graalvm.sdk"
        , "substratevm", "org.graalvm.nativeimage"
    );

    static final Map<String, String> ARTIFACT_IDS = Map.of(
        "sdk", "graal-sdk"
        , "substratevm", "svm"
    );

    static void mvn(Build build)
    {
        // Only invoke mvn if all mx builds succeeded
        final var releaseArtifacts =
            build.options.artifactNames.stream()
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
        return artifactName ->
        {
            final var artifact =
                Maven.artifact(build.paths)
                    .apply(artifactName);

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
        // TODO fix deploy plugin version (in pom.xml)
        return artifact ->
            new OperatingSystem.Command(
                Stream.of(
                    "mvn"
                    , build.options.verbose ? "--debug" : ""
                    , "deploy"
                    , String.format("-DrepositoryId=%s", build.options.mavenRepoId)
                    , String.format("-Durl=%s", build.options.mavenURL)
                )
                , build.paths.workingDir
                , Stream.empty()
            );
    }

    private static Function<String, Artifact> artifact(LocalPaths paths)
    {
        return artifactName ->
        {
            final var rootPath = LocalPaths
                .graalHome(paths)
                .apply(Path.of(artifactName));
            final var groupId = GROUP_IDS.get(artifactName);
            final var artifactId = ARTIFACT_IDS.get(artifactName);

            return new Artifact(
                rootPath
                , groupId
                , artifactId
            );
        };
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
            .compose(Maven.mvnInstallSnapshot(build.options))
            .apply(artifact);
    }

    private static Function<Artifact, OperatingSystem.Command> mvnInstallSnapshot(Options options)
    {
        // TODO fix install-file plugin version -> https://maven.apache.org/plugins/maven-install-plugin/examples/custom-pom-installation.html
        return artifact ->
            new OperatingSystem.Command(
                Stream.of(
                    "mvn"
                    , options.verbose ? "--debug" : ""
                    , "install:install-file"
                    , String.format("-DgroupId=%s", artifact.groupId)
                    , String.format("-DartifactId=%s", artifact.artifactId)
                    , String.format("-Dversion=%s", Options.snapshotVersion(options))
                    , "-Dpackaging=jar"
                    , String.format(
                        "-Dfile=%s/mxbuild/dists/jdk11/%s.jar"
                        , artifact.rootPath.toString()
                        , artifact.artifactId
                    )
                    , String.format(
                        "-Dsources=%s/mxbuild/dists/jdk11/%s.src.zip"
                        , artifact.rootPath.toString()
                        , artifact.artifactId
                    )
                    , "-DcreateChecksum=true"
                )
                , artifact.rootPath
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
        return artifact -> {
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
        // TODO fix install-file plugin version -> https://maven.apache.org/plugins/maven-install-plugin/examples/custom-pom-installation.html
        return artifact ->
            new OperatingSystem.Command(
                Stream.of(
                    "mvn"
                    , build.options.verbose ? "--debug" : ""
                    , "install:install-file"
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
        final Path rootPath;
        final String groupId;
        final String artifactId;

        Artifact(Path rootPath, String groupId, String artifactId)
        {
            this.rootPath = rootPath;
            this.groupId = groupId;
            this.artifactId = artifactId;
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
