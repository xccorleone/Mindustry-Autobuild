package autobuild.bootstrap;

/** Offline tests of the bootstrap domain: run from the repository root (see test.sh). */
public class BootstrapTest{
    public static void main(String[] args) throws Exception{
        CatalogTest.run();
        BlueprintCodecTest.run();
        ModuleAnalyzerTest.run();
        SurveyTest.run();
        ChainSolverTest.run();
        System.out.println(Check.fails == 0 ? "BOOTSTRAP OK" : Check.fails + " FAILED");
        if(Check.fails > 0) System.exit(1);
    }
}
