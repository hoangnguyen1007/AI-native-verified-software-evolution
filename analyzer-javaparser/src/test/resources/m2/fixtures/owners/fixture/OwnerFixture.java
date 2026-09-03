package fixture;

final class OwnerFixture {
    void first() {
        class Local {
            Local() {}
            class Nested {
                String trim(String input) { return input.trim(); }
            }
            String trim(String input) { return input.trim(); }
        }
        new Local().trim("a");
    }

    void second() {
        class Local {
            Local() {}
            class Nested {
                String trim(String input) { return input.trim(); }
            }
            String trim(String input) { return input.trim(); }
        }
        new Local().trim("b");
    }
}
