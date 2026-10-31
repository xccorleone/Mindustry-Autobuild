package autobuild.bootstrap;

import autobuild.bootstrap.domain.module.Blueprint;

import java.util.Arrays;

import static autobuild.bootstrap.Check.*;

final class BlueprintCodecTest{
    static void run() throws Exception{
        // version 0: configs are ints (absolute bridge targets, item ids)
        Blueprint si = blueprint(FIXTURES + "silicon8.txt");
        eq("v0 name", "Silicon Smelter (8)", si.name);
        eq("v0 size", "15x7", si.width + "x" + si.height);
        eq("v0 tiles", 39, si.tiles.size());
        eq("v0 bridge link is relative", "[0, -2]", Arrays.toString((int[])si.at(4, 5).config));
        eq("v0 sorter item", "silicon", si.at(4, 1).config);
        eq("v0 rotation", 3, si.at(4, 6).rot);

        // version 1: TypeIO objects
        Blueprint gen = blueprint(FIXTURES + "generator1.txt");
        eq("v1 size", "6x6", gen.width + "x" + gen.height);
        eq("v1 liquid bridge link", "[1, 0]", Arrays.toString((int[])gen.at(0, 3).config));
        eq("v1 block", "steam-generator", gen.at(1, 4).block);

        // version 1 with a contentMap tag (item ids remapped by name)
        Blueprint kiln = blueprint(LOCAL + "metaglass4.txt");
        if(kiln != null){
            eq("contentMap sorter", "sand", kiln.at(3, 1).config);
            eq("contentMap sorter 2", "lead", kiln.at(4, 1).config);
            eq("power node links", 5, ((int[][])kiln.at(2, 3).config).length);
        }
    }
}
