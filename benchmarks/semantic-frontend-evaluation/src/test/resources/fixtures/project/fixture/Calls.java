package fixture;

import java.util.List;

class Calls {
    String invoke(List<String> values) {
        return values.get(0).trim();
    }
}
