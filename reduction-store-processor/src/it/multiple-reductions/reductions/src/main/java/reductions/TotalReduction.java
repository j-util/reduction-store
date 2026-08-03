package reductions;

import io.github.jutil.reductionstore.Reduction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import model.Row;

public final class TotalReduction
        implements Reduction<Row, BigDecimal> {

    public static final List<Row> received = new ArrayList<Row>();

    @Override
    public Supplier<BigDecimal> supplier() {
        return () -> BigDecimal.ZERO;
    }

    @Override
    public BiFunction<BigDecimal, Row, BigDecimal> reducer() {
        return (total, row) -> {
            received.add(row);
            return total.add(BigDecimal.valueOf(row.value()));
        };
    }
}
