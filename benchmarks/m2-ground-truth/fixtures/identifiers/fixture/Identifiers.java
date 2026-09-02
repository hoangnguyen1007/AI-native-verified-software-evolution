package fixture;
class Identifiers {
    static void café() {}
    static void café() {}
    static void ignored() {}
    static void escaped() {}
    static void exercise() {
        café();
        café();
        ig‌nored();
        ignored();
        \u0065scaped();
        escaped();
    }
}
