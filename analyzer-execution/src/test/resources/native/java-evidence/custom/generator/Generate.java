import java.nio.file.Files;
import java.nio.file.Path;
public final class Generate {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root.resolve("java/fixture"));
        Files.createDirectories(root.resolve("resources/META-INF/evo"));
        Files.writeString(root.resolve("java/fixture/Generated.java"),
                "package fixture; " + (args.length == 2 && args[1].equals("spring")
                        ? "@org.springframework.context.annotation.ComponentScan(\"fixture\") " : "")
                        + "public final class Generated { public static String value() { return \"native\"; } }\n");
        Files.writeString(root.resolve("resources/META-INF/evo/generated.properties"), "generated.value=native\n");
        if (args.length == 2 && args[1].equals("spring")) {
            Files.writeString(root.resolve("java/fixture/Choice.java"), "package fixture; public interface Choice {}\n");
            Files.writeString(root.resolve("java/fixture/BlueChoice.java"),
                    "package fixture; @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile(\"blue\") public final class BlueChoice implements Choice {}\n");
            Files.writeString(root.resolve("java/fixture/OtherChoice.java"),
                    "package fixture; @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile(\"!blue\") public final class OtherChoice implements Choice {}\n");
        }
    }
}
