//usr/bin/env jbang "$0" "$@" ; exit $?
// *** picocli ***
//DEPS info.picocli:picocli:4.1.4

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(
    description = "Build mandrel bits"
    , mixinStandardHelpOptions = true
    , name = "build"
    , version = "build 0.1"
)
public class build implements Callable<Integer>
{
    public static void main(String... args)
    {
        int exitCode = new CommandLine(new build()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call()
    {
        System.out.println("Do build!");
        return 0;
    }
}
