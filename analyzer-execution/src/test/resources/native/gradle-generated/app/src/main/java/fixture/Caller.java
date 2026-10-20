package fixture;
public class Caller { public static void main(String[] args) { if (Generated.value() + Helper.delta() != 43) throw new AssertionError(); } }
