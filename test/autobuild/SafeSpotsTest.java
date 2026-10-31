package autobuild;

import autobuild.drones.SafeSpots;

/** Offline tests of the drones' safe-spot geometry: run from the repository root (see test.sh). */
public class SafeSpotsTest{
    static int fails;

    public static void main(String[] args){
        float[] out = new float[2];

        // a turret circle (r 100) at the origin, work point 60 east of it, work range 120:
        // the spot must be outside the circle and within range of the work point
        SafeSpots s = new SafeSpots();
        s.add(0, 0, 100);
        check("work spot found", s.workSpot(300, 0, 60, 0, 120, out));
        check("work spot safe", s.safe(out[0], out[1]));
        check("work spot in range", dst(out[0], out[1], 60, 0) <= 120.01f);
        check("work spot on the drone's side", out[0] > 100);

        // drone on the far side: the straight flight would cross the circle, the spot must not
        check("around the circle", s.workSpot(-300, 0, 60, 0, 120, out) ? s.clearPath(-300, 0, out[0], out[1]) : true);

        // already in range and safe: the nearest spot is close to where the drone is
        check("near spot", s.workSpot(170, 0, 60, 0, 120, out) && dst(out[0], out[1], 170, 0) < 40);

        // work point deep inside the circle, range too short to reach outside: no spot
        check("no spot deep inside", !s.workSpot(300, 0, 10, 0, 50, new float[2]));

        // paths: crossing is refused, leaving is allowed, going deeper is refused
        check("crossing refused", !s.clearPath(-200, 0, 200, 0));
        check("tangent outside ok", s.clearPath(-200, 120, 200, 120));
        check("leaving ok", s.clearPath(50, 0, 200, 0));
        check("deeper refused", !s.clearPath(80, 0, 20, 0));

        // refuge: a safe reachable home wins
        check("home", s.refuge(50, 0, 400, 0, out) == SafeSpots.Refuge.home && out[0] == 400);
        // home inside the circle: a ring point, safe and reachable, on the leaving side
        SafeSpots.Refuge r = s.refuge(50, 0, -10, 0, out);
        check("ring refuge", r == SafeSpots.Refuge.ring && s.safe(out[0], out[1]) && s.clearPath(50, 0, out[0], out[1]));
        // no home: still a safe point
        check("refuge without home", s.refuge(50, 0, Float.NaN, Float.NaN, out) == SafeSpots.Refuge.ring && s.safe(out[0], out[1]));

        // everything within reach is dangerous: flee straight away from the nearest circle
        SafeSpots all = new SafeSpots();
        all.add(0, 0, 2000);
        check("away", all.refuge(100, 0, Float.NaN, Float.NaN, out) == SafeSpots.Refuge.away && out[0] > 100 && Math.abs(out[1]) < 0.01f);

        // map bounds: no spot outside the world
        SafeSpots b = new SafeSpots();
        b.bounds(400, 400);
        b.add(200, 200, 150);
        check("in bounds", b.workSpot(200, 380, 200, 300, 120, out) && out[1] <= 400 && b.safe(out[0], out[1]));

        // no danger: the spot is on the ring towards the drone
        SafeSpots none = new SafeSpots();
        check("no danger", none.workSpot(500, 0, 0, 0, 100, out) && Math.abs(out[0] - 95) < 0.5f && Math.abs(out[1]) < 0.5f);

        System.out.println(fails == 0 ? "SAFE SPOTS OK" : fails + " FAILED");
        if(fails > 0) System.exit(1);
    }

    static void check(String what, boolean ok){
        if(!ok){
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    static float dst(float x1, float y1, float x2, float y2){
        return (float)Math.hypot(x1 - x2, y1 - y2);
    }
}
