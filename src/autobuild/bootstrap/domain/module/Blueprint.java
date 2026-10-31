package autobuild.bootstrap.domain.module;

import java.util.*;

/**
 * A game schematic in neutral form. Configs are normalized to: {@code String} (an item or liquid
 * name), {@code int[]{dx, dy}} (a link relative to the block), {@code int[][]} (several links),
 * {@code Integer}, or null for anything else.
 */
public final class Blueprint{
    public static final class Tile{
        public final String block;
        public final int x, y, rot;
        public final Object config;

        public Tile(String block, int x, int y, int rot, Object config){
            this.block = block;
            this.x = x;
            this.y = y;
            this.rot = rot;
            this.config = config;
        }

        @Override public String toString(){ return block + "(" + x + "," + y + ")r" + rot; }
    }

    public final String name;
    public final int width, height;
    public final List<Tile> tiles;

    public Blueprint(String name, int width, int height, List<Tile> tiles){
        this.name = name;
        this.width = width;
        this.height = height;
        this.tiles = List.copyOf(tiles);
    }

    public Tile at(int x, int y){
        for(Tile t : tiles) if(t.x == x && t.y == y) return t;
        return null;
    }
}
