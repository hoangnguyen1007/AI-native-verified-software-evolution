package javax.lookalike;
import java.util.List;
class Bindings {
    static void choose(int value) {}
    static void choose(Integer value) {}
    static <T> T identity(T value) { return value; }
    static void spread(String... values) {}
    static void spread(Object value) {}
    static void exercise(List<String> values, Integer boxed) {
        choose(1);
        choose(boxed);
        choose((short) 1);
        values.get(0).trim();
        identity("x").trim();
        spread("a", "b");
        spread((Object) "a");
        spread(new String[] {"a"});
    }
}
