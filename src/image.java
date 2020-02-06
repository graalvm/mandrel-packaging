//usr/bin/env jbang "$0" "$@" ; exit $?
// *** hamcrest ***
//DEPS org.hamcrest:hamcrest-library:2.2
// *** picocli ***
//DEPS info.picocli:picocli:4.1.4

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

@Command(
    description = "Image builder"
    , mixinStandardHelpOptions = true
    , name = "image"
    , subcommands =
    {
        BuildImageCommand.class
        , RunImageCommand.class
    }
    , version = "image builder 0.1"
)
class image implements Callable<Integer>
{
    public static void main(String... args)
    {
        int exitCode = new CommandLine(new image()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call()
    {
        System.out.println("No-op");
        return 0;
    }
}

@Command(
    description = "Build command to run builder image"
    , name = "run-image-command"
)
class RunImageCommand implements Callable<Void>
{
    @Option(
        description = "local packaging repo clone"
        , names = {"-p", "--local-packaging-clone"}
    )
    File localPackagingClone;

    @Override
    public Void call() throws Exception
    {
        System.out.println("docker run -it --entrypoint /bin/bash mandrel-packaging");
        return null;
    }

    static String dockerRunOptions(File localClone)
    {
        if (localClone != null)
        {
            return String.format(
                "-v %s:/home/mandrel/mandrel-packaging -e SKIP_CLONE=1"
                , localClone
            );
        }

        return "";
    }
}

@Command(
    description = "Build command to build packaging image"
    , name = "build-image-command"
)
class BuildImageCommand implements Callable<Void>
{
    @Override
    public Void call()
    {
        // --no-squash so that layers can be cached
        System.out.println("cekit -v build docker --no-squash");
        return null;
    }
}
