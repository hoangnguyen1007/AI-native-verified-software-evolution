package fixture;

class DuplicateCalls {
    void first(String value) { value.trim(); }
    void second(String value) { value.trim(); }
}
