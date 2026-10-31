package autobuild.bootstrap;

import autobuild.bootstrap.domain.survey.Terrain;

/**
 * A map drawn as text, top row first: '.' floor, '#' wall, 'C' core, ores 'c' copper, 'l' lead,
 * 's' sand, 'k' coal, 't' titanium, '~' shallow water (buildable), 'W' deep water (not buildable).
 */
final class GridTerrain implements Terrain{
    final char[][] g;
    final int w, h;
    final int[] core = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};

    GridTerrain(String... rows){
        h = rows.length;
        w = rows[0].length();
        g = new char[w][h];
        for(int y = 0; y < h; y++) for(int x = 0; x < w; x++){
            char c = rows[h - 1 - y].charAt(x);
            g[x][y] = c;
            if(c == 'C'){
                core[0] = Math.min(core[0], x);
                core[1] = Math.min(core[1], y);
                core[2] = Math.max(core[2], x);
                core[3] = Math.max(core[3], y);
            }
        }
    }

    public int width(){ return w; }

    public int height(){ return h; }

    public String ore(int x, int y){
        return switch(g[x][y]){
            case 'c' -> "copper";
            case 'l' -> "lead";
            case 's' -> "sand";
            case 'k' -> "coal";
            case 't' -> "titanium";
            default -> null;
        };
    }

    public boolean buildable(int x, int y){ return g[x][y] != '#' && g[x][y] != 'C' && g[x][y] != 'W'; }

    public String liquid(int x, int y){ return g[x][y] == '~' || g[x][y] == 'W' ? "water" : null; }

    public int[] core(){ return core; }
}
