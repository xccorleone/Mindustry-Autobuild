package autobuild.bootstrap.domain.staging;

import java.util.*;

/** How steps are valued and when one is worth waiting for. */
public final class StageOptions{
    /** value of one item (income and cost alike); items not listed are worth {@link #defaultWeight} */
    public final Map<String, Double> weights = new TreeMap<>(Map.of(
        "titanium", 2.0, "graphite", 2.0, "metaglass", 2.5, "silicon", 3.0));
    public double defaultWeight = 1;
    /** seconds: candidates affordable within this are preferred */
    public double horizon = 600;
    /** most of one item the core holds */
    public int capacity = 4000;
    /** building costs nothing (sandbox) */
    public boolean infinite;

    public double weight(String item){ return weights.getOrDefault(item, defaultWeight); }
}
