package fixture;
class Offsets {
    static void hit() {}
    static void take(String value) {}
    static void exercise() {
		hit(); hit();
        take(
		    "multi"
        );
        String astral = "😀"; hit();
        take("😀");
        \u0068it();
        // escaped line ends comment: \u000a hit();
    }
}
