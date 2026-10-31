package autobuild.bootstrap;

import autobuild.bootstrap.domain.catalog.Catalog;

import java.io.IOException;
import java.nio.file.*;

/** Assertions counting failures (the offline tests are plain mains, like PlannerTest), and fixtures. */
final class Check{
    static final String FIXTURES = "test/autobuild/bootstrap/fixtures/", LOCAL = "test/local/blueprints/";
    static int fails;
    static Catalog catalog;

    static void eq(String what, Object expected, Object actual){
        if(!String.valueOf(expected).equals(String.valueOf(actual))){
            fails++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }

    static void near(String what, double expected, double actual){
        if(Math.abs(expected - actual) > 1e-3 * Math.max(1, Math.abs(expected))){
            fails++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }

    static void yes(String what, boolean ok){
        if(!ok){
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    static Catalog catalog() throws IOException{
        if(catalog == null) catalog = Catalog.parse(Files.readString(Path.of(FIXTURES + "catalog-v160.7.txt")));
        return catalog;
    }
}
