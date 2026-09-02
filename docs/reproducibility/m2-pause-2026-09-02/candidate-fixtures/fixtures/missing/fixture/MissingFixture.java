package fixture;

import missing.api.MissingType;

final class MissingFixture {
    MissingType missing;

    String run(String value) {
        value.trim();
        return missing.work();
    }

    MissingType build() { return new MissingType(); }
}
