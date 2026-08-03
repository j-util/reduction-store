package example;

import io.github.jutil.reductionstore.LongReducer;
import io.github.jutil.reductionstore.LongReduction;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

public final class Total implements LongReduction<Row> {

    static final List<Row> received = new ArrayList<Row>();

    @Override
    public LongSupplier supplier() {
        return () -> 0L;
    }

    @Override
    public LongReducer<Row> reducer() {
        return (total, row) -> {
            received.add(row);
            return total + row.value();
        };
    }
}
