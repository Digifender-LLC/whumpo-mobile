import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;

// Source-launched with the configured JDK; use Java's actual properties grammar.
public class ReadSdkProperties {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected local properties path");
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        for (var key : new String[]{"sdk.dir", "android.dir"}) {
            var value = properties.getProperty(key);
            System.out.println(key + ":" + (value == null ? "-"
                : Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
        }
    }
}
