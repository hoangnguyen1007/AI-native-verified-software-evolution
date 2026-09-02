package fixture;
class MissingType {
    static void known() {}
    static void exercise() {
        MissingDependency value = null;
        known();
        value.absent();
    }
}
