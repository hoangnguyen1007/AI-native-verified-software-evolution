package fixture;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@interface Inner {}
@interface Outer { Inner value(); }
sealed interface Shape permits Box {}
record Box(String text) implements Shape {
    Box { text = text.trim(); }
    int size() { return this.text.length(); }
}
enum Mode {
    ON { int code() { return Helper.hit(); } };
    abstract int code();
}
class Helper { static int hit() { return 1; } }
@Outer(@Inner)
class Modern extends Object {
    int count;
    <T extends Number> T echo(T value) throws IOException { return value; }
    int run(Object input) {
        List<String> words = List.of("x");
        Function<Integer, String> at = words::get;
        Supplier<Box> make = () -> new Box("a");
        Supplier<Mode[]> modes = Mode::values;
        int length = switch (input) {
            case Box(String text) -> text.length();
            case String text -> text.length();
            default -> 0;
        };
        count += length;
        return count + at.apply(0).length() + make.get().size() + modes.get()[0].code();
    }
}
