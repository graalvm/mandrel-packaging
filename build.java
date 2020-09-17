import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class build
{
    static final Logger logger = LogManager.getLogger(build.class);

    public static void main(String... args) throws IOException, InterruptedException {
        Check.main();

        final var options = Options.from(Args.read(args));

        final var fs = FileSystem.ofSystem(options);
        final var os = new OperatingSystem();
        final var build = new SequentialBuild(fs, os);
        final var mandrelRepo = FileSystem.mandrelRepo(options);

        logger.info("Building!");
        if (options.mandrelVersion == null) {
            options.mandrelVersion = getMandrelVersion(os, mandrelRepo);
        }
        final String mandrelVersionUntilSpace = options.mandrelVersion.split(" ")[0];

        build.build(options);

        logger.info("Creating JDK!");
        // logger.info("Copy base JDK!");
        final var mandrelHome = FileSystem.mandrelHome(options);
        FileSystem.deleteDirectoryIfExists(mandrelHome.toFile());
        fs.copyDirectory(os.javaHome(), mandrelHome);

        // logger.info("Copy jars!");
        Maven.ARTIFACT_IDS.forEach(artifact -> {
            final String source = mandrelRepo.resolve(Maven.DISTS_PATHS.get(artifact)).toString();
            final String destination = mandrelHome.resolve(Maven.JDK_PATHS.get(artifact)).toString();
            FileSystem.copy(Path.of(source + ".jar"), Path.of(destination + ".jar"));
            FileSystem.copy(Path.of(source + ".src.zip"), Path.of(destination + ".src.zip"));
        });

        // logger.info("Copy docs!");
        FileSystem.copy(mandrelRepo.resolve("LICENSE"), mandrelHome.resolve("LICENSE"));
        FileSystem.copy(mandrelRepo.resolve("THIRD_PARTY_LICENSE.txt"), mandrelHome.resolve("THIRD_PARTY_LICENSE.txt"));
        if (mandrelRepo.resolve("README-Mandrel.md").toFile().exists()) {
            FileSystem.copy(mandrelRepo.resolve("README-Mandrel.md"), mandrelHome.resolve("README.md"));
        } else {
            FileSystem.copy(mandrelRepo.resolve("README.md"), mandrelHome.resolve("README.md"));
        }
        FileSystem.copy(mandrelRepo.resolve("SECURITY.md"), mandrelHome.resolve("SECURITY.md"));

        // logger.info("Native bits!");
        final String OS = System.getProperty("os.name").toLowerCase();
        final String ARCH = System.getProperty("os.arch");
        final String PLATFORM = OS + "-" + ARCH;
        FileSystem.copy(mandrelRepo.resolve(Path.of("substratevm", "src", "com.oracle.svm.native.libchelper", "include", "amd64cpufeatures.h")),
                mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "amd64cpufeatures.h")));
        FileSystem.copy(mandrelRepo.resolve(Path.of("substratevm", "src", "com.oracle.svm.native.libchelper", "include", "aarch64cpufeatures.h")),
                mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "aarch64cpufeatures.h")));
        FileSystem.copy(mandrelRepo.resolve(Path.of("substratevm", "src", "com.oracle.svm.libffi", "include", "svm_libffi.h")),
                mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "svm_libffi.h")));
        FileSystem.copy(mandrelRepo.resolve(Path.of("truffle", "src", "com.oracle.truffle.nfi.native", "include", "trufflenfi.h")),
                mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "trufflenfi.h")));
        // TODO account for OS in library names
        FileSystem.copy(mandrelRepo.resolve(Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.libchelper", ARCH, "liblibchelper.a")),
                mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "liblibchelper.a")));
        // TODO On windows don't copy jvm.posix
        FileSystem.copy(mandrelRepo.resolve(Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.jvm.posix", ARCH, "libjvm.a")),
                mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "libjvm.a")));
        final Path nativeImage = mandrelHome.resolve(Path.of("lib", "svm", "bin", "native-image"));
        FileSystem.copy(mandrelRepo.resolve(Path.of("sdk", "mxbuild", PLATFORM, "native-image.image-bash", "native-image")),
                nativeImage);
        Files.createSymbolicLink(mandrelHome.resolve(Path.of("bin", "native-image")), Path.of("..", "lib", "svm", "bin", "native-image"));

        // Patch native image
        patchNativeImageLauncher(nativeImage, options.mandrelVersion);

        logger.info("Congratulations you successfully built Mandrel " + mandrelVersionUntilSpace + " based on Java " + System.getProperty("java.runtime.version"));
        logger.info("You can find your newly built native-image enabled JDK under " + mandrelHome);

        if (options.archiveSuffix != null) {
            int javaMajor = Runtime.version().feature();
            String archiveName = "mandrel-java" + javaMajor + "-" + PLATFORM + "-" + mandrelVersionUntilSpace + "." + options.archiveSuffix;
            logger.info("Creating Archive " + archiveName);
            createArchive(fs, os, mandrelHome, archiveName);
        }
    }

    private static void createArchive(FileSystem fs, OperatingSystem os, Path mandrelHome, String archiveName) {
        final String mandrelRoot = mandrelHome.getParent().toString();
        final String mandrelRepository = mandrelHome.getFileName().toString();
        Tasks.Exec tarCommand;
        if (archiveName.endsWith("tar.gz")) {
            tarCommand = Tasks.Exec.of(
                    Arrays.asList("tar", "-czf", archiveName, "-C", mandrelRoot, mandrelRepository),
                    fs.workingDir());
        } else if (archiveName.endsWith("tarxz")) {
            tarCommand = Tasks.Exec.of(
                    Arrays.asList("tar", "cJf", archiveName, "-C", mandrelRoot, mandrelRepository),
                    fs.workingDir(),
                    new EnvVar("XZ_OPT", "-9e"));
        } else {
            throw new IllegalArgumentException("Unsupported archive suffix. Please use one of tar.gz or tarxz");
        }
        os.exec(tarCommand);
    }

    private static String getMandrelVersion(OperatingSystem os, Path mandrelRepo) {
        // git -C ${MANDREL_REPO} describe 2>/dev/null || git -C ${MANDREL_REPO} rev-parse --short HEAD) | sed 's/mandrel-//'
        var command = Tasks.Exec.of(Arrays.asList("git", "describe"), mandrelRepo);
        String output = null;
        try {
            output = os.exec(command).findFirst().orElse("dev");
        } catch (RuntimeException e) {
            command = Tasks.Exec.of(Arrays.asList("git", "rev-parse", "--short", "HEAD"), mandrelRepo);
            output = os.exec(command).findFirst().orElse("dev");
        }
        return output.replace("mandrel-", "");
    }

    private static void patchNativeImageLauncher(Path nativeImage, String mandrelVersion) throws IOException {
        List<String> lines = Files.readAllLines(nativeImage);
        final Pattern pattern = Pattern.compile("(.*EnableJVMCI)(.*JDK9Plus')(.*)");
        for (int i = 0; i < lines.size(); i++) {
            final Matcher matcher = pattern.matcher(lines.get(i));
            if (matcher.find()) {
                StringBuilder newLine = new StringBuilder(matcher.group(1));
                newLine.append(" -Dorg.graalvm.version=\"" + mandrelVersion + "\"");
                newLine.append(" -Dorg.graalvm.config=\"(Mandrel Distribution)\"");
                newLine.append(" --upgrade-module-path ${location}/../../jvmci/graal.jar");
                newLine.append(" --add-modules \"org.graalvm.truffle,org.graalvm.sdk\"");
                newLine.append(" --module-path ${location}/../../truffle/truffle-api.jar:${location}/../../jvmci/graal-sdk.jar");
                newLine.append(matcher.group(2));
                newLine.append(" -J--add-exports=jdk.internal.vm.ci/jdk.vm.ci.code=jdk.internal.vm.compiler");
                newLine.append(matcher.group(3));
                lines.set(i, newLine.toString());
                break;
            }
        }
        Files.write(nativeImage, lines, StandardCharsets.UTF_8);
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
    final MavenAction mavenAction;
    String mavenVersion;
    String mandrelVersion;
    final boolean verbose;
    final String mavenProxy;
    final String mavenRepoId;
    final String mavenURL;
    final String mavenLocalRepository;
    final String mandrelRepo;
    final String mandrelHome;
    final String mxHome;
    final List<Dependency> dependencies;
    final boolean skipClean;
    final boolean skipJava;
    final boolean skipNative;
    final String mavenHome;
    final String archiveSuffix;

    Options(
        MavenAction action
        , String mavenVersion
        , String mandrelVersion
        , boolean verbose
        , String mavenProxy
        , String mavenRepoId
        , String mavenURL
        , String mavenLocalRepository
        , String mandrelRepo
        , String mandrelHome
        , String mxHome
        , List<Dependency> dependencies
        , boolean skipClean
        , boolean skipJava
        , boolean skipNative
        , String mavenHome
        , String archiveSuffix
    )
    {
        this.mavenAction = action;
        this.mavenVersion = mavenVersion;
        this.mandrelVersion = mandrelVersion;
        this.verbose = verbose;
        this.mavenProxy = mavenProxy;
        this.mavenRepoId = mavenRepoId;
        this.mavenURL = mavenURL;
        this.mavenLocalRepository = mavenLocalRepository;
        this.mandrelRepo = mandrelRepo;
        this.mandrelHome = mandrelHome;
        this.mxHome = mxHome;
        this.dependencies = dependencies;
        this.skipClean = skipClean;
        this.skipJava = skipJava;
        this.skipNative = skipNative;
        this.mavenHome = mavenHome;
        this.archiveSuffix = archiveSuffix;
    }

    public static Options from(Map<String, List<String>> args)
    {
        // Maven related
        var mavenAction = MavenAction.NOP;
        if (args.containsKey("maven-install")) {
            mavenAction = MavenAction.INSTALL;
        }
        if (args.containsKey("maven-deploy")) {
            mavenAction = MavenAction.DEPLOY;
        }
        final var mavenVersion = required("maven-version", args, mavenAction != MavenAction.NOP);
        final var mavenProxy = optional("maven-proxy", args);
        final var mavenRepoId = required("maven-repo-id", args, mavenAction == MavenAction.DEPLOY);
        final var mavenURL = required("maven-url", args, mavenAction == MavenAction.DEPLOY);
        final var mavenLocalRepository = optional("maven-local-repository", args);
        final var mavenHome = optional("maven-home", args);

        // Mandrel related
        final var mandrelVersion = optional("mandrel-version", args);
        final var mandrelHome = optional("mandrel-home", args);
        final var mandrelRepo = optional("mandrel-repo", args);

        final var verbose = args.containsKey("verbose");
        Logger.debug = verbose;

        final var mxHome = optional("mx-home", args);

        final var dependenciesArg = args.get("dependencies");
        final var dependencies = dependenciesArg == null
            ? Collections.<Dependency>emptyList()
            : toDependencies(dependenciesArg);

        final var skipClean = args.containsKey("skip-clean");
        final var skipJava = args.containsKey("skip-java");
        final var skipNative = args.containsKey("skip-native");

        final var archiveSuffix = optional("archive-suffix", args);

        return new Options(
            mavenAction
            , mavenVersion
            , mandrelVersion
            , verbose
            , mavenProxy
            , mavenRepoId
            , mavenURL
            , mavenLocalRepository
            , mandrelRepo
            , mandrelHome
            , mxHome
            , dependencies
            , skipClean
            , skipJava
            , skipNative
            , mavenHome
            , archiveSuffix
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

    static String snapshotVersion(Options options)
    {
        return String.format("%s-SNAPSHOT", options.mavenVersion);
    }

    private static String optional(String name, Map<String, List<String>> args)
    {
        return required(name, args, false);
    }

    private static String required(String name, Map<String, List<String>> args, boolean required)
    {
        final var option = args.get(name);
        if (Objects.isNull(option)) {
            if (required) {
                throw new IllegalArgumentException(String.format("Missing mandatory --%s", name));
            }
            return null;
        }

        if (option.size() == 0) {
            throw new IllegalArgumentException(String.format("Missing mandatory value for --%s", name));
        }

        return option.get(0);
    }

    enum MavenAction
    {
        INSTALL, DEPLOY, NOP
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
        Mx.build(options, exec, replace, fs::mxHome, fs::mandrelRepo, os::javaHome);
        if (options.mavenAction != Options.MavenAction.NOP && !options.skipJava) {
            final var maven = Maven.of(fs::mavenHome, fs::mavenRepoHome);
            maven.mvn(options, exec, replace, fs::mandrelRepo, fs::workingDir);
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
    private static final Logger LOG = LogManager.getLogger(Mx.class);

    private static final Pattern DEPENDENCY_SHA1_PATTERN =
        Pattern.compile("\"sha1\"\\s*:\\s*\"([a-f0-9]*)\"");
    private static final Pattern DEPENDENCY_SOURCE_SHA1_PATTERN =
        Pattern.compile("\"sourceSha1\"\\s*:\\s*\"([a-f0-9]*)\"");
    private static final Pattern DEPENDENCY_VERSION_PATTERN =
        Pattern.compile("\"version\"\\s*:\\s*\"([0-9.]*)\"");

    static final List<BuildArgs> BUILD_JAVA_STEPS = List.of(
            BuildArgs.of("--no-native", "--dependencies", "SVM,SVM_DRIVER")
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
        , Function<Path, Path> mandrelRepo
        , Supplier<Path> javaHome
    )
    {
        Mx.swapDependencies(options, replace, mxHome);
        Mx.removeDependencies(replace, mxHome, mandrelRepo);
        Mx.hookMavenProxy(options, replace, mxHome);

        final var clean = !options.skipClean;
        if (clean)
            exec.exec.accept(Mx.mxclean(options, mxHome, mandrelRepo));

        if(!options.skipJava) {
            BUILD_JAVA_STEPS.stream()
                    .map(Mx.mxbuild(options, mxHome, mandrelRepo, javaHome))
                    .forEach(exec.exec);
        }

        if(!options.skipNative) {
            BUILD_NATIVE_STEPS.stream()
                    .map(Mx.mxbuild(options, mxHome, mandrelRepo, javaHome))
                    .forEach(exec.exec);
        }
    }

    private static Tasks.Exec mxclean(
        Options options
        , Function<Path, Path> mxHome
        , Function<Path, Path> mandrelRepo
    )
    {
        final var mx = mxHome.apply(Paths.get("mx"));
        return Tasks.Exec.of(
            Arrays.asList(
                mx.toString()
                , options.verbose ? "-V" : ""
                , "clean"
            )
            , mandrelRepo.apply(Path.of("substratevm"))
        );
    }

    private static Function<BuildArgs, Tasks.Exec> mxbuild(
        Options options
        , Function<Path, Path> mxHome
        , Function<Path, Path> mandrelRepo
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
                , mandrelRepo.apply(Path.of("substratevm"))
            );
        };
    }

    static void removeDependencies(Tasks.FileReplace.Effects effects, Function<Path, Path> mxHome, Function<Path, Path> mandrelRepo)
    {
        LOG.debugf("Remove dependencies");
        final var suitePy = Path.of("substratevm", "mx.substratevm", "suite.py");
        final var path = mandrelRepo.apply(suitePy);
        final var dependencies = Arrays.asList("truffle:TRUFFLE_NFI",
                "com.oracle.svm.truffle", "com.oracle.svm.polyglot", "truffle:TRUFFLE_API",
                "com.oracle.svm.truffle.nfi", "com.oracle.svm.truffle.nfi.posix", "com.oracle.svm.truffle.nfi.windows",
                "extracted-dependency:truffle:LIBFFI_DIST", "extracted-dependency:truffle:TRUFFLE_NFI_NATIVE/include/*",
                "file:src/com.oracle.svm.libffi/include/svm_libffi.h");

        Tasks.FileReplace.replace(
                new Tasks.FileReplace(path, removeDependencies(dependencies))
                , effects
        );
    }

    private static Function<Stream<String>, List<String>> removeDependencies(List<String> dependencies)
    {
        return lines ->
        {
            return lines.filter(l -> dependenciesFilter(l, dependencies)).collect(Collectors.toList());
        };
    }

    private static boolean dependenciesFilter(String line, List<String> dependencies) {
        for (String dependency : dependencies) {
            if (line.contains("\"" + dependency + "\",")) {
                LOG.debugf("REMOVING dependency : " + dependency);
                return false;
            }
        }
        LOG.debugf("KEEPING : " + line);
        return true;
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

    static final Map<String, Path> JDK_PATHS = Map.of(
        "graal-sdk", Path.of("lib", "jvmci", "graal-sdk")
        , "svm", Path.of("lib", "svm", "builder", "svm")
        , "pointsto", Path.of("lib", "svm", "builder", "pointsto")
        , "truffle-api", Path.of("lib", "truffle", "truffle-api")
        , "compiler", Path.of("lib", "jvmci", "graal")
        , "objectfile", Path.of("lib", "svm", "builder", "objectfile")
        , "svm-driver", Path.of("lib", "graalvm", "svm-driver")
    );

    final Path mvn;
    final Function<Path, Path> repoHome;

    private Maven(Path mvn, Function<Path, Path> repoHome) {
        this.mvn = mvn;
        this.repoHome = repoHome;
    }

    static Maven of(Supplier<Path> lazyHome, Function<Path, Path> repoHome) {
        final var home = lazyHome.get();
        final var mvn = home.toString().isEmpty()
            ? Path.of("mvn")
            : Path.of("bin", "mvn");
        return new Maven(home.resolve(mvn), repoHome);
    }

    void mvn(
        Options options
        , Tasks.Exec.Effects exec
        , Tasks.FileReplace.Effects replace
        , Supplier<Path> mandrelRepo
        , Function<Path, Path> workingDir
    )
    {
        // Only invoke mvn if all mx builds succeeded
        final var releaseArtifacts =
            ARTIFACT_IDS.stream()
                .map(mvnInstall(options, exec, replace, mandrelRepo, workingDir))
                .collect(Collectors.toList());

        // Only deploy if all mvn installs worked
        if (options.mavenAction == Options.MavenAction.DEPLOY)
        {
            releaseArtifacts.forEach(mvnDeploy(options, exec, workingDir));
        }
    }

    private Function<String, ReleaseArtifact> mvnInstall(
        Options options
        , Tasks.Exec.Effects exec
        , Tasks.FileReplace.Effects replace
        , Supplier<Path> mandrelRepo
        , Function<Path, Path> workingDir
    )
    {
        return artifactId ->
        {
            final var artifact =
                Maven.artifact(artifactId);

            exec.exec.accept(mvnInstallSnapshot(artifact, options, mandrelRepo));
            exec.exec.accept(mvnInstallAssembly(artifact, options, replace, workingDir));

            final var releaseArtifact = ReleaseArtifact.of(artifact, options, workingDir, repoHome);
            exec.exec.accept(mvnInstallRelease(releaseArtifact, options, replace, workingDir));

            return releaseArtifact;
        };
    }

    private Consumer<ReleaseArtifact> mvnDeploy(Options options, Tasks.Exec.Effects exec, Function<Path, Path> workingDir)
    {
        return artifact ->
            exec.exec.accept(deploy(artifact, options, workingDir));
    }

    private Tasks.Exec deploy(ReleaseArtifact artifact, Options options, Function<Path, Path> workingDir)
    {
         return Tasks.Exec.of(
                Arrays.asList(
                    mvn.toString()
                    , options.verbose ? "--debug" : ""
                    , DEPLOY_FILE_GOAL
                    , String.format("-DgroupId=%s", artifact.groupId)
                    , String.format("-DartifactId=%s", artifact.artifactId)
                    , String.format("-Dversion=%s", options.mavenVersion)
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
                .map(line -> line.replace("999", options.mavenVersion))
                .collect(Collectors.toList());
    }

    private Tasks.Exec mvnInstallSnapshot(Artifact artifact, Options options, Supplier<Path> mandrelRepo)
    {
        return Tasks.Exec.of(
                Arrays.asList(
                    mvn.toString()
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
                , mandrelRepo.get()
            );
    }

    private Tasks.Exec mvnInstallAssembly(
        Artifact artifact
        , Options options
        , Tasks.FileReplace.Effects effects
        , Function<Path, Path> workingDir
    )
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
                mvn.toString()
                , options.verbose ? "--debug" : ""
                , "install"
            )
            , paths.target.getParent()
        );
    }

    private Tasks.Exec mvnInstallRelease(
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
                mvn.toString()
                , options.verbose ? "--debug" : ""
                , INSTALL_FILE_GOAL
                , String.format("-DgroupId=%s", releaseArtifact.groupId)
                , String.format("-DartifactId=%s", releaseArtifact.artifactId)
                , String.format("-Dversion=%s", options.mavenVersion)
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

        // TODO Release artifact should not depend on mavenRepoHome,
        //      it can be built with relative paths throughout.
        //      The final paths can be computed when constructing the mvn command.
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
                , options.mavenVersion
            );

            final var artifactPath = mavenRepoHome
                .apply(Path.of(artifact.groupId.replace(".", "/")))
                .resolve(artifact.artifactId);

            final var jarPath = artifactPath
                .resolve(String.format("%s-ASSEMBLY", options.mavenVersion))
                .resolve(jarName);

            final var sourceJarName = String.format(
                "%s-%s-SNAPSHOT-sources.jar"
                , artifact.artifactId
                , options.mavenVersion
            );

            final var sourceJarPath = artifactPath
                .resolve(String.format("%s-SNAPSHOT", options.mavenVersion))
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

    private final Path mandrelRepo;
    private final Path mxHome;
    private final Path workingDir;
    private final Path mavenRepoHome;
    private final Path mavenHome;

    FileSystem(Path mandrelRepo, Path mxHome, Path workingDir, Path mavenRepoHome, Path mavenHome) {
        this.mandrelRepo = mandrelRepo;
        this.mxHome = mxHome;
        this.workingDir = workingDir;
        this.mavenRepoHome = mavenRepoHome;
        this.mavenHome = mavenHome;
    }

    Path mxHome(Path relative)
    {
        return mxHome.resolve(relative);
    }

    Path mandrelRepo(Path relative)
    {
        return mandrelRepo.resolve(relative);
    }

    Path mandrelRepo()
    {
        return mandrelRepo;
    }

    Path workingDir()
    {
        return workingDir;
    }

    Path workingDir(Path relative)
    {
        return workingDir.resolve(relative);
    }

    Path mavenRepoHome(Path relative)
    {
        return mavenRepoHome.resolve(relative);
    }

    Path mavenHome()
    {
        return mavenHome;
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

    public static void deleteDirectoryIfExists(File directory) {
        if (!directory.exists()) {
            return;
        }
        assert directory.isDirectory();
        final File[] files = directory.listFiles();
        if (files != null) {
            for (var f : files) {
                if (f.isDirectory()) {
                    deleteDirectoryIfExists(f);
                } else {
                    f.delete();
                }
            }
        }
        directory.delete();
    }

    void copyDirectory(Path source, Path destination) {
        assert source.toFile().isDirectory();
        assert !destination.toFile().exists() || destination.toFile().isDirectory();
        CopyVisitor copyVisitor = new CopyVisitor(source, destination);
        try {
            Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, copyVisitor);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class CopyVisitor extends SimpleFileVisitor<Path> {
        private final Path source;
        private final Path destination;

        public CopyVisitor(Path source, Path destination) {
            this.source = source;
            this.destination = destination;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            final Path relativePath = source.relativize(file);
            copy(file, destination.resolve(relativePath));
            return FileVisitResult.CONTINUE;
        }
    }

    static FileSystem ofSystem(Options options)
    {
        final var mandrelRepo = mandrelRepo(options);
        final var mxHome = mxHome(options);
        final var userDir = System.getProperty("user.dir");
        final var workingDir = new File(userDir).toPath();
        final var mavenRepoHome = mavenRepoHome(options);
        final var mavenHome = mavenHome(options);
        return new FileSystem(mandrelRepo, mxHome, workingDir, mavenRepoHome, mavenHome);
    }

    static Path mandrelRepo(Options options)
    {
        if (options.mandrelRepo == null)
        {
            return Path.of("/tmp", "mandrel");
        }

        return Path.of(options.mandrelRepo);
    }

    static Path mandrelHome(Options options)
    {
        if (options.mandrelHome == null)
        {
            int javaMajor = Runtime.version().feature();
            return Path.of(".", "mandrel-java" + javaMajor + "-" + options.mandrelVersion.split(" ")[0]);
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

    private static Path mavenHome(Options options)
    {
        return Path.of(Objects.requireNonNullElse(options.mavenHome, ""));
    }
}

class OperatingSystem
{
    static final Logger LOG = LogManager.getLogger(OperatingSystem.class);

    Stream<String> exec(Tasks.Exec task)
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

            final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            var output = bufferedReader.lines();

            if (process.waitFor() != 0)
            {
                throw new RuntimeException(
                    "Failed, exit code: " + process.exitValue()
                );
            }

            return output;
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
    static boolean debug;

    final String name;

    Logger(String name)
    {
        this.name = name;
    }

    void debugf(String format, Object... params)
    {
        if (debug) {
            System.out.printf("DEBUG [%s] %s%n"
                    , name
                    , String.format(format, params)
            );
        }
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
        final var options = Options.from(Args.read("--maven-version-suffix", ".redhat-00001"));
        final var os = new RecordingOperatingSystem();
        final var exec = new Tasks.Exec.Effects(os::record);
        final var replace = Tasks.FileReplace.Effects.noop();
        final Function<Path, Path> identity = Function.identity();
        final Supplier<Path> javaHome = () -> Path.of("java");
        Mx.build(options, exec, replace, identity, identity, javaHome);
        os.assertNumberOfTasks(4);
        os.assertTask("mx clean");
        os.assertTask("mx --trust-http --java-home java build --no-native --dependencies SVM,SVM_DRIVER");
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
