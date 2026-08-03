package composition;

import io.github.jutil.reductionstore.ReductionStoreDefinition;
import model.Row;
import reductions.CountReduction;
import reductions.TotalReduction;

@ReductionStoreDefinition(
        input = Row.class,
        reductions = {
                TotalReduction.class,
                CountReduction.class
        }
)
interface RowStoreDefinition {
}
