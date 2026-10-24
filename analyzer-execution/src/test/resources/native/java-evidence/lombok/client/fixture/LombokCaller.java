package fixture;
public final class LombokCaller {
    public static Person person() { return Person.builder().name("native").build(); }
    public static Person construct() { return new Person("native"); }
    public static void main(String[] args) {
        if (!person().getName().equals("native")) throw new AssertionError();
        if (!construct().getName().equals("native")) throw new AssertionError();
    }
}
