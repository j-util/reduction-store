package example;

public final class SourceRow {

    private final String rawValue;

    public SourceRow(String rawValue) {
        this.rawValue = rawValue;
    }

    public String rawValue() {
        return rawValue;
    }
}
