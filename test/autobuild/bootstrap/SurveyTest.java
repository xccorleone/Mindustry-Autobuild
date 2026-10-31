package autobuild.bootstrap;

import autobuild.bootstrap.domain.survey.*;

import java.util.List;

import static autobuild.bootstrap.Check.*;

final class SurveyTest{
    static final GridTerrain MAP = new GridTerrain(
        "cc......~W",
        "cc..CC..~W",
        "....CC....",
        "ss......kk",
        "ss......kk");

    static void run(){
        SurveyService.Report r = SurveyService.survey(MAP, new SurveyService.Options());
        eq("next to core", 1, r.field.at(3, 2));
        eq("one step out", 2, r.field.at(2, 2));
        eq("patches", 3, r.patches.size());
        eq("copper", "copper x4 d3", one(r.patches("copper")));
        eq("sand", "sand x4 d3", one(r.patches("sand")));
        eq("coal (bridge from the core's ring)", "coal x4 d3", one(r.patches("coal")));
        eq("water bodies", 1, r.waters.size());
        eq("water size (deep included)", 4, r.waters.get(0).size());
        eq("water distance", 3, r.waters.get(0).distance);

        SurveyService.Options danger = new SurveyService.Options();
        danger.danger = DangerMask.around(MAP.width(), MAP.height(), List.of(new int[]{9, 0}), 1.5f);
        eq("coal in danger", 0, SurveyService.survey(MAP, danger).patches("coal").size());

        SurveyService.Options base = new SurveyService.Options();
        base.base = new int[]{0, 0, 6, 4};
        SurveyService.Report inBase = SurveyService.survey(MAP, base);
        eq("coal outside base", 0, inBase.patches("coal").size());
        eq("water outside base", 0, inBase.waters.size());

        // a lake across the base edge keeps only its tiles inside; danger cuts it the same way
        SurveyService.Options half = new SurveyService.Options();
        half.base = new int[]{0, 0, 8, 4};
        eq("lake cut at the base edge", 2, SurveyService.survey(MAP, half).waters.get(0).size());
        SurveyService.Options wet = new SurveyService.Options();
        wet.danger = DangerMask.around(MAP.width(), MAP.height(), List.of(new int[]{9, 4}), 0.5f);
        eq("danger cuts the lake", 3, SurveyService.survey(MAP, wet).waters.get(0).size());

        // a wall between the core and the ore: only a bridge gets over it
        GridTerrain walled = new GridTerrain("C.##..k.");
        eq("bridge over wall", 3, SurveyService.survey(walled, new SurveyService.Options()).field.at(4, 0));
        eq("ore behind wall", "coal x1 d4", one(SurveyService.survey(walled, new SurveyService.Options()).patches("coal")));
        SurveyService.Options noBridge = new SurveyService.Options();
        noBridge.bridgeRange = 1;
        SurveyService.Report noWay = SurveyService.survey(walled, noBridge);
        eq("no bridge, no way", PathField.UNREACHABLE, noWay.field.at(4, 0));
        eq("unreachable ore left out", 0, noWay.patches("coal").size());
    }

    static String one(List<OrePatch> ps){
        if(ps.size() != 1) return ps.size() + " patches";
        return ps.get(0).item + " x" + ps.get(0).size() + " d" + ps.get(0).distance;
    }
}
