package fixture;

@MissingMarker
class Caller implements Runnable {

    private final Service service = new Service();

    String call() {
        return service.choose("value");
    }

    @Override
    public void run() {
    }
}
