package fixture;
public final class MapStructCaller {
    public static Output map(Input input) { return new PersonMapperImpl().map(input); }
    public static void main(String[] args) {
        Input input = new Input();
        input.name = "native";
        if (!map(input).name.equals("native")) throw new AssertionError();
    }
}
