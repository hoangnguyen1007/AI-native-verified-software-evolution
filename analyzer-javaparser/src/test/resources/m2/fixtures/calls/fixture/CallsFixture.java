package fixture;

import java.util.List;

final class CallsFixture {
    CallsFixture() {}

    String choose(String value) { return value; }

    String choose(Object value) { return value.toString(); }

    String run(List<String> values) {
        String first = choose("x");
        String second = choose((Object) "x");
        return values.get(0).trim();
    }

    CallsFixture make() { return new CallsFixture(); }

    String copy(String value) { return new String(value); }
}
