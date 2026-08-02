package example;

public final class Application {

    private Application() {
    }

    public static void main(String[] arguments) {
        RowReductionStore store = new RowReductionStore();
        store.add(new Row(10L));
        store.add(new Row(20L));
        store.add(new Row(30L));

        if (store.count() != 3L) {
            throw new AssertionError(
                    "Expected count 3 but got " + store.count());
        }
        if (store.total() != 60L) {
            throw new AssertionError(
                    "Expected total 60 but got " + store.total());
        }
    }
}
