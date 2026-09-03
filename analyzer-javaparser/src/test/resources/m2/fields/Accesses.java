package fixture.fields;
import static java.lang.Integer.MAX_VALUE;
class Base { int inherited; }
class Box { int value; Box next; int[] numbers; }
class Accesses extends Base {
    int own;
    Box box;
    void run(Box arg, int own) {
        int local = own;
        this.own = local;
        this.own += arg.value;
        ++this.own;
        this.own--;
        (this.own) = 5;
        box.value = arg.value;
        box.next.value++;
        box.numbers[0] = local;
        own++;
        local = this.own;
        inherited = local;
        super.inherited++;
        local = MAX_VALUE;
        System.out.println(java.lang.Integer.MAX_VALUE);
    }
}
