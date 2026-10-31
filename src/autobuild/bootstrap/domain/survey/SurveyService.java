package autobuild.bootstrap.domain.survey;

import java.util.*;

/**
 * Explores the map from the core: the line-cost field, ore patches and water bodies, all limited to
 * usable tiles - buildable, inside the base area if one is set, outside the danger mask (enemy routes
 * and their reach).
 */
public final class SurveyService{
    public static final class Options{
        public int bridgeRange = 4;
        /** base area {x0, y0, x1, y1}, or null for the whole map */
        public int[] base;
        /** tiles nothing may be built on (enemy routes and range), or null */
        public boolean[][] danger;
    }

    public static final class Report{
        public final PathField field;
        /** nearest first */
        public final List<OrePatch> patches;
        public final List<WaterBody> waters;

        Report(PathField field, List<OrePatch> patches, List<WaterBody> waters){
            this.field = field;
            this.patches = patches;
            this.waters = waters;
        }

        public List<OrePatch> patches(String item){
            return patches.stream().filter(p -> p.item.equals(item)).toList();
        }
    }

    private SurveyService(){}

    public static Report survey(Terrain t, Options o){
        int w = t.width(), h = t.height();
        boolean[] usable = new boolean[w * h];
        for(int x = 0; x < w; x++) for(int y = 0; y < h; y++){
            boolean inBase = o.base == null || (x >= o.base[0] && y >= o.base[1] && x <= o.base[2] && y <= o.base[3]);
            usable[x + y * w] = inBase && t.buildable(x, y) && (o.danger == null || !o.danger[x][y]);
        }
        PathField field = PathField.compute(w, h, usable, t.core(), o.bridgeRange);
        List<OrePatch> patches = new ArrayList<>();
        boolean[] seen = new boolean[w * h];
        for(int i = 0; i < w * h; i++){
            String ore = t.ore(i % w, i / w);
            if(seen[i] || !usable[i] || ore == null) continue;
            int[] tiles = flood(i, w, h, true, j -> usable[j] && ore.equals(t.ore(j % w, j / w)), seen);
            int d = PathField.UNREACHABLE;
            for(int j : tiles) d = Math.min(d, field.dist[j]);
            patches.add(new OrePatch(ore, tiles, w, d));
        }
        List<WaterBody> waters = new ArrayList<>();
        Arrays.fill(seen, false);
        for(int i = 0; i < w * h; i++){
            String liquid = t.liquid(i % w, i / w);
            boolean inBase = o.base == null || (i % w >= o.base[0] && i / w >= o.base[1] && i % w <= o.base[2] && i / w <= o.base[3]);
            if(seen[i] || liquid == null || !inBase) continue;
            int[] tiles = flood(i, w, h, false, j -> liquid.equals(t.liquid(j % w, j / w)), seen);
            int d = PathField.UNREACHABLE;
            for(int j : tiles){
                d = Math.min(d, field.dist[j]);
                int x = j % w, y = j / w;
                for(int[] n : new int[][]{{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}}){
                    int nd = field.at(n[0], n[1]);
                    if(nd != PathField.UNREACHABLE) d = Math.min(d, nd + 1);
                }
            }
            waters.add(new WaterBody(liquid, tiles, d));
        }
        patches.sort(Comparator.comparingInt(p -> p.distance));
        waters.sort(Comparator.comparingInt(b -> b.distance));
        return new Report(field, patches, waters);
    }

    static int[] flood(int start, int w, int h, boolean eight, java.util.function.IntPredicate same, boolean[] seen){
        List<Integer> out = new ArrayList<>();
        Deque<Integer> q = new ArrayDeque<>();
        q.add(start);
        seen[start] = true;
        while(!q.isEmpty()){
            int i = q.poll();
            out.add(i);
            int x = i % w, y = i / w;
            for(int dx = -1; dx <= 1; dx++) for(int dy = -1; dy <= 1; dy++){
                if((dx == 0 && dy == 0) || (!eight && dx != 0 && dy != 0)) continue;
                int nx = x + dx, ny = y + dy;
                if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int n = nx + ny * w;
                if(!seen[n] && same.test(n)){
                    seen[n] = true;
                    q.add(n);
                }
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }
}
