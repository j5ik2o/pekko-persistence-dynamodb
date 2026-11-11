import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

public final class RuntimeProbe {
    public static void premain(String arguments, Instrumentation instrumentation) throws IOException {
        String[] parts = arguments.split(":", 2);
        if (parts.length != 2 || !parts[0].matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("invalid runtime probe arguments");
        }
        Path directory = Path.of(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        String record = parts[0] + "\n" + encode(System.getProperty("java.version"))
                + "\n" + encode(System.getProperty("java.home")) + "\n";
        Files.writeString(directory.resolve(ProcessHandle.current().pid() + ".probe"), record,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
