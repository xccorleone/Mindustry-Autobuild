package autobuild.bootstrap.domain.module;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.InflaterInputStream;

/**
 * Decodes the game's schematic format ("msch", versions 0 and 1, as {@code mindustry.game.Schematics}
 * reads it): header, version byte, then deflate data - width, height, tags, block names, and per tile
 * block index, packed position, config, rotation. Version 0 stores configs as ints mapped by block
 * type; version 1 stores {@code TypeIO} objects (only the types schematics carry are read).
 */
public final class BlueprintCodec{
    static final int CONTENT_ITEM = 0, CONTENT_LIQUID = 4;

    private BlueprintCodec(){}

    public static Blueprint decodeBase64(String text, Catalog catalog) throws IOException{
        return decode(Base64.getDecoder().decode(text.trim()), catalog);
    }

    public static Blueprint decode(byte[] bytes, Catalog catalog) throws IOException{
        InputStream in = new ByteArrayInputStream(bytes);
        for(byte b : "msch".getBytes()) if(in.read() != b) throw new IOException("not a schematic (missing header)");
        int ver = in.read();
        if(ver > 1) throw new IOException("unknown schematic version " + ver);
        try(DataInputStream s = new DataInputStream(new InflaterInputStream(in))){
            int w = s.readShort(), h = s.readShort();
            Map<String, String> tags = new HashMap<>();
            int tagCount = s.readUnsignedByte();
            for(int i = 0; i < tagCount; i++) tags.put(s.readUTF(), s.readUTF());
            Map<Integer, Map<Integer, String>> contentMap = contentMap(tags.get("contentMap"));
            String[] names = new String[s.readUnsignedByte()];
            for(int i = 0; i < names.length; i++) names[i] = s.readUTF();
            int total = s.readInt();
            List<Blueprint.Tile> tiles = new ArrayList<>();
            for(int i = 0; i < total; i++){
                String block = names[s.readByte()];
                int pos = s.readInt();
                int x = (short)(pos >>> 16), y = (short)(pos & 0xFFFF);
                Object config = ver == 0 ? mapConfig(catalog.block(block), s.readInt(), x, y, catalog) : readObject(s, catalog, contentMap);
                int rot = s.readByte();
                tiles.add(new Blueprint.Tile(block, x, y, rot, config));
            }
            return new Blueprint(tags.getOrDefault("name", "?"), w, h, tiles);
        }
    }

    /**
     * version 0: the game's Schematics.mapConfig for the configs the module analyzer reads - sorter and
     * item source items, liquid source liquids, bridge links (made relative). Unloader, mass driver and
     * illuminator configs, which the game also maps, come back as null.
     */
    static Object mapConfig(BlockInfo b, int value, int x, int y, Catalog catalog){
        if(b == null) return null;
        switch(b.kind){
            case "sorter", "itemsource" -> {
                Catalog.ItemInfo it = catalog.itemById(value);
                return it == null ? null : it.name;
            }
            case "liquidsource" -> { return catalog.liquidById(value); }
            case "bridge", "liquidbridge" -> { return new int[]{(short)(value >>> 16) - x, (short)(value & 0xFFFF) - y}; }
            default -> { return null; }
        }
    }

    /** version 1: TypeIO.readObject, for the object types a schematic config can hold */
    static Object readObject(DataInputStream s, Catalog catalog, Map<Integer, Map<Integer, String>> contentMap) throws IOException{
        int type = s.readByte();
        switch(type){
            case 0: return null;
            case 1: return s.readInt();
            case 2: s.readLong(); return null;
            case 3: s.readFloat(); return null;
            case 4: return s.readByte() != 0 ? s.readUTF() : null;
            case 5: {
                int ct = s.readByte(), id = s.readShort();
                Map<Integer, String> mapped = contentMap.get(ct);
                if(mapped != null && mapped.containsKey(id)) return mapped.get(id);
                if(ct == CONTENT_ITEM){
                    Catalog.ItemInfo it = catalog.itemById(id);
                    return it == null ? null : it.name;
                }
                return ct == CONTENT_LIQUID ? catalog.liquidById(id) : null;
            }
            case 6: { int n = s.readShort(); for(int i = 0; i < n; i++) s.readInt(); return null; }
            case 7: return new int[]{s.readInt(), s.readInt()};
            case 8: {
                int n = s.readUnsignedByte();
                int[][] out = new int[n][];
                for(int i = 0; i < n; i++){
                    int p = s.readInt();
                    out[i] = new int[]{(short)(p >>> 16), (short)(p & 0xFFFF)};
                }
                return out;
            }
            case 9: s.readByte(); s.readShort(); return null;
            case 10: s.readBoolean(); return null;
            case 11: s.readDouble(); return null;
            case 12: s.readInt(); return null;
            case 13: s.readShort(); return null;
            case 14: { int n = s.readInt(); s.readFully(new byte[n]); return null; }
            case 15: s.readByte(); return null;
            case 16: { int n = s.readInt(); for(int i = 0; i < n; i++) s.readBoolean(); return null; }
            case 17: s.readInt(); return null;
            case 18: { int n = s.readShort(); for(int i = 0; i < n * 2; i++) s.readFloat(); return null; }
            case 19: s.readFloat(); s.readFloat(); return null;
            case 20: s.readUnsignedByte(); return null;
            case 21: { int n = s.readShort(); for(int i = 0; i < n; i++) s.readInt(); return null; }
            case 22: { int n = s.readInt(); for(int i = 0; i < n; i++) readObject(s, catalog, contentMap); return null; }
            case 23: s.readUnsignedShort(); return null;
            default: throw new IOException("unknown config type " + type);
        }
    }

    static final Pattern TYPE = Pattern.compile("(\\d+)\\s*:\\s*\\{([^}]*)\\}"), ENTRY = Pattern.compile("\"?([\\w-]+)\"?\\s*:\\s*(-?\\d+)");

    /** tag "contentMap": {type: {name: id, ...}, ...} (hjson, names unquoted) -> type -> id -> name */
    static Map<Integer, Map<Integer, String>> contentMap(String tag){
        Map<Integer, Map<Integer, String>> out = new HashMap<>();
        if(tag == null) return out;
        Matcher t = TYPE.matcher(tag);
        while(t.find()){
            Map<Integer, String> inner = out.computeIfAbsent(Integer.parseInt(t.group(1)), k -> new HashMap<>());
            Matcher e = ENTRY.matcher(t.group(2));
            while(e.find()) inner.put(Integer.parseInt(e.group(2)), e.group(1));
        }
        return out;
    }
}
