package fixture;
class Malformed {
    static void known() {}
    static void exercise() {
        known();
        int broken = ;
    }
}
