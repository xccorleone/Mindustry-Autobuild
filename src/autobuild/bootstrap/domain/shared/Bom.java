package autobuild.bootstrap.domain.shared;

import java.util.*;

/** Item amounts by item name (a bill of materials, or a recipe's inputs / outputs). */
public final class Bom{
    final TreeMap<String, Integer> amounts = new TreeMap<>();

    /** "copper:30,lead:25" (empty string = empty bill) */
    public static Bom parse(String text){
        Bom b = new Bom();
        if(text == null || text.isEmpty()) return b;
        for(String part : text.split(",")){
            int c = part.lastIndexOf(':');
            b.add(part.substring(0, c), Integer.parseInt(part.substring(c + 1)));
        }
        return b;
    }

    public Bom add(String item, int n){
        if(n != 0) amounts.merge(item, n, Integer::sum);
        return this;
    }

    public Bom addAll(Bom o){
        o.amounts.forEach(this::add);
        return this;
    }

    public int get(String item){ return amounts.getOrDefault(item, 0); }

    public Set<String> items(){ return Collections.unmodifiableSet(amounts.keySet()); }

    public boolean isEmpty(){ return amounts.isEmpty(); }

    @Override public boolean equals(Object o){ return o instanceof Bom b && b.amounts.equals(amounts); }

    @Override public int hashCode(){ return amounts.hashCode(); }

    @Override public String toString(){
        StringJoiner j = new StringJoiner(",");
        amounts.forEach((k, v) -> j.add(k + ":" + v));
        return j.toString();
    }
}
