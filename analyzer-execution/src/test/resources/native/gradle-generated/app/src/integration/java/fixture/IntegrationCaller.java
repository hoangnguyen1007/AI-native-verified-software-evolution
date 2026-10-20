package fixture;
public class IntegrationCaller { public static void main(String[] args) { if (Generated.value() != 42) throw new AssertionError(); } }
