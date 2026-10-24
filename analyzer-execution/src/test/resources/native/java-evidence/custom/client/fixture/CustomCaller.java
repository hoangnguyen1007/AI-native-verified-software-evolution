package fixture;
public final class CustomCaller {
    public static String value() { return Generated.value(); }
    public static void main(String[] args) {
        if (!value().equals("native")) throw new AssertionError();
    }
}
