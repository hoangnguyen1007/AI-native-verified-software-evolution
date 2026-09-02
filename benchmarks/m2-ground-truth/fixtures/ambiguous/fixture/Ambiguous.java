package fixture;
class Ambiguous {
    static void pick(String value) {}
    static void pick(Integer value) {}
    static void exercise() {
        pick(null);
    }
}
