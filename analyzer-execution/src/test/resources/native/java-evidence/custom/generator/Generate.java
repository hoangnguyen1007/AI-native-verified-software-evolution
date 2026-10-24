import java.nio.file.Files;
import java.nio.file.Path;
public final class Generate {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root.resolve("java/fixture"));
        Files.createDirectories(root.resolve("resources/META-INF/evo"));
        Files.writeString(root.resolve("java/fixture/Generated.java"),
                "package fixture; public final class Generated { public static String value() { return \"native\"; } }\n");
        Files.writeString(root.resolve("resources/META-INF/evo/generated.properties"), "generated.value=native\n");
    }
}
