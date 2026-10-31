package autobuild.bootstrap;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.production.*;
import autobuild.bootstrap.domain.survey.*;

import static autobuild.bootstrap.Check.*;

final class ChainSolverTest{
    /** 40 x 20: sand x0..9 (200 tiles), coal x30..34 y0..9 (50), copper x30..39 y12..19 (80), water x36..39 y0..9 (40) */
    static GridTerrain map(){ return map(10, 10); }

    /** the same with sand in the first `sandCols` columns and coal below row `coalTop` */
    static GridTerrain map(int sandCols, int coalTop){
        String[] rows = new String[20];
        for(int y = 0; y < 20; y++){
            StringBuilder s = new StringBuilder();
            for(int x = 0; x < 40; x++){
                char c = '.';
                if(x < sandCols) c = 's';
                else if(x >= 30 && x <= 34 && y < coalTop) c = 'k';
                else if(x >= 30 && y >= 12) c = 'c';
                else if(x >= 36 && y < 10) c = '~';
                else if((x == 20 || x == 21) && (y == 10 || y == 11)) c = 'C';
                s.append(c);
            }
            rows[19 - y] = s.toString();
        }
        return new GridTerrain(rows);
    }

    static ProductionGoal goal(double belt) throws Exception{
        ProductionGoal g = new ProductionGoal();
        g.modules.add(new ProductionGoal.ModuleOrder(ModuleAnalyzer.analyze(blueprint(FIXTURES + "silicon8.txt"), catalog(), 10), 1));
        g.power = ModuleAnalyzer.analyze(blueprint(FIXTURES + "generator1.txt"), catalog(), 10);
        g.raw.put("copper", 1);
        g.drills.add(catalog().block("mechanical-drill"));
        g.drills.add(catalog().block("pneumatic-drill"));
        g.pump = catalog().block("mechanical-pump");
        g.belt = belt;
        return g;
    }

    static void run() throws Exception{
        SurveyService.Report survey = SurveyService.survey(map(), new SurveyService.Options());
        ProductionPlan p = ChainSolver.solve(goal(10), survey, catalog());
        eq("instances: silicon + one plant", 2, p.instances.size());
        yes("second is the plant", p.instances.get(1).power);
        near("power demand", 240, p.powerDemand);
        near("power supply", 1320, p.powerSupply);
        eq("lines", 6, p.lines.size());

        // coal: 50 tiles x 0.8 x 60/500 (pneumatic) = 4.8/s; the plant's 2.67 first, silicon gets the rest
        SupplyLine plantCoal = line(p, 1, 1), siCoal = line(p, 0, 0), sand = line(p, 0, 1), copper = line(p, -1, -1);
        near("plant coal", 4 * 60 / 90.0, plantCoal.supplied);
        near("silicon coal", 4.8 - 4 * 60 / 90.0, siCoal.supplied);
        yes("silicon coal limits", siCoal.limiting);
        near("silicon factor", (4.8 - 4 * 60 / 90.0) / 10, p.instances.get(0).factor);
        near("sand scaled to the coal", 4.8 - 4 * 60 / 90.0, sand.target);
        yes("sand not limiting", !sand.limiting);
        near("sand wanted", 10, sand.wanted);
        near("plant water", 24, line(p, 1, 0).supplied);
        // copper: 80 tiles x 0.8 x 60/450 = 8.53/s for a 10/s line
        near("copper", 80 * 0.8 * 60 / 450, copper.supplied);
        yes("copper limiting", copper.limiting);
        eq("shortfalls", 2, p.shortfalls().size());
        eq("no belt limit at 10/s", 0, p.beltLimited.size());

        ProductionPlan slow = ChainSolver.solve(goal(5), survey, catalog());
        eq("basic belts: silicon inlets limited", 3, slow.beltLimited.size());
        near("silicon sand line at belt speed", 5, line(slow, 0, 1).wanted);

        ProductionGoal noPlant = goal(10);
        noPlant.power = null;
        ProductionPlan np = ChainSolver.solve(noPlant, survey, catalog());
        yes("no power plant", np.noPower);
        yes("no plant, power short", np.powerShort);
        eq("only silicon", 1, np.instances.size());

        // plenty of coal (60 tiles: 5.76/s) but basic belts: each 10/s inlet gets 5, so silicon runs at half speed
        SurveyService.Report rich = SurveyService.survey(map(10, 12), new SurveyService.Options());
        ProductionGoal belts = goal(5);
        belts.power = null;
        belts.raw.clear();
        ProductionPlan b = ChainSolver.solve(belts, rich, catalog());
        near("belts halve the module", 0.5, b.instances.get(0).factor);
        eq("belts are no ore shortfall", 0, b.shortfalls().size());

        // little sand: 40 tiles give 4.8/s for two 10/s inlets - the module runs at 0.24, the sand split over both
        SurveyService.Report sandy = SurveyService.survey(map(2, 12), new SurveyService.Options());
        ProductionGoal fewSand = goal(10);
        fewSand.power = null;
        fewSand.raw.clear();
        ProductionPlan s = ChainSolver.solve(fewSand, sandy, catalog());
        near("scarce sand sets the speed", 0.24, s.instances.get(0).factor);
        near("sand split, line 1", 2.4, line(s, 0, 1).supplied);
        near("sand split, line 2", 2.4, line(s, 0, 2).supplied);
        eq("sand is the bottleneck", 2, s.shortfalls().size());
        yes("sand lines limit", line(s, 0, 1).limiting && line(s, 0, 2).limiting);
        yes("the short coal line doesn't", !line(s, 0, 0).limiting);

        // a core sand line next to a coal-starved module gets the sand the module no longer needs
        ProductionGoal shared = goal(10);
        shared.power = null;
        shared.raw.clear();
        shared.raw.put("sand", 1);
        ProductionPlan sh = ChainSolver.solve(shared, survey, catalog());
        near("core sand filled after the slowdown", 10, line(sh, -1, -1).supplied);
        yes("core sand not limiting", !line(sh, -1, -1).limiting);
        eq("coal is the only bottleneck", 1, sh.shortfalls().size());

        // goals the solver refuses
        Catalog cat = catalog();
        ProductionGoal noBelt = goal(10);
        noBelt.belt = 0;
        yes("belt speed required", throwsIae(() -> ChainSolver.solve(noBelt, survey, cat)));
        ProductionGoal broken = goal(10);
        broken.modules.add(new ProductionGoal.ModuleOrder(new ModuleSpec("x", 1, 1), 1));
        broken.modules.get(1).spec.error = "no inlet";
        yes("unusable module refused", throwsIae(() -> ChainSolver.solve(broken, survey, cat)));

        // graphite first: scarce coal (15 tiles, 1.44/s) goes to the press before the silicon smelter
        SurveyService.Report fewCoal = SurveyService.survey(map(10, 3), new SurveyService.Options());
        ProductionGoal pressFirst = goal(10);
        pressFirst.power = null;
        pressFirst.raw.clear();
        pressFirst.modules.add(new ProductionGoal.ModuleOrder(ModuleAnalyzer.analyze(BuiltinModules.graphitePress(), catalog(), 10), 1));
        ProductionPlan pf = ChainSolver.solve(pressFirst, fewCoal, catalog());
        yes("the press makes a first product", pf.instances.get(1).first && !pf.instances.get(0).first);
        near("the press runs at full speed", 1, pf.instances.get(1).factor);
        pressFirst.first.clear();
        ProductionPlan bigFirst = ChainSolver.solve(pressFirst, fewCoal, catalog());
        yes("without it the smelter takes the coal", bigFirst.instances.get(1).factor < 0.5);

        // no long line for a little ore: a 2-tile lead patch far from the core is left out, the near one used
        GridTerrain leads = map(0, 10);
        for(int x = 14; x <= 17; x++) for(int y = 13; y <= 15; y++) leads.g[x][y] = 'l';
        leads.g[1][18] = leads.g[2][18] = 'l';
        SurveyService.Report lr = SurveyService.survey(leads, new SurveyService.Options());
        ProductionGoal lead = goal(10);
        lead.modules.clear();
        lead.power = null;
        lead.raw.clear();
        lead.raw.put("lead", 1);
        SupplyLine ll = line(ChainSolver.solve(lead, lr, catalog()), -1, -1);
        eq("lead: only the near patch", 1, ll.sources.size());
        eq("lead: the near patch", 12, ll.sources.get(0).patch.size());
        lead.fetch = 0;
        eq("lead: both without the rule", 2, line(ChainSolver.solve(lead, lr, catalog()), -1, -1).sources.size());

        // no coal at all: the plant burns nothing and makes no power
        ProductionPlan dark = ChainSolver.solve(goal(10), SurveyService.survey(map(10, 0), new SurveyService.Options()), catalog());
        near("starved plant", 0, dark.powerSupply);
        yes("power short", dark.powerShort);
        yes("plant fuel limits", line(dark, 1, 1).limiting);
    }

    static boolean throwsIae(Runnable r){
        try{
            r.run();
            return false;
        }catch(IllegalArgumentException e){
            return true;
        }
    }

    static SupplyLine line(ProductionPlan p, int instance, int inlet){
        for(SupplyLine l : p.lines) if(l.instance == instance && l.inlet == inlet) return l;
        throw new AssertionError("no line " + instance + "." + inlet);
    }
}
