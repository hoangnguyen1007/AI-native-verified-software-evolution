package fixture;
public class Caller {
    public static void main(String[] args) {
        if (Generated.value() != 42) throw new AssertionError("wrong generated value");
    }
}
