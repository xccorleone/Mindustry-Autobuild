package autobuild.drones;

/**
 * Where a support drone can work or wait without being shot. Danger is a set of circles (world
 * units: enemy turret and unit ranges, places where the drone got hurt); spots are picked from rings
 * of candidates. Pure geometry, no game classes (tested offline).
 */
public final class SafeSpots{
    /** candidates per ring */
    static final int dirs = 16;
    /** work spot rings, fractions of the work range (outer first: the least detour) */
    static final float[] workRings = {0.95f, 0.7f, 0.4f};
    /** refuge rings around the drone: step and farthest, world units (3 and 60 tiles) */
    static final float refugeStep = 24f, refugeMax = 480f;
    /** a drone already inside a circle may only leave it: the flight may come this much closer */
    static final float leaveSlack = 8f;

    float[] cx = new float[32], cy = new float[32], cr = new float[32];
    int n;
    /** world size, world units; 0 = unbounded */
    float width, height;

    /** kind of the last {@link #refuge} */
    public enum Refuge{ home, ring, unsafePath, away }

    public void clear(){ n = 0; }

    public void bounds(float width, float height){
        this.width = width;
        this.height = height;
    }

    public void add(float x, float y, float r){
        if(n == cx.length){
            cx = java.util.Arrays.copyOf(cx, n * 2);
            cy = java.util.Arrays.copyOf(cy, n * 2);
            cr = java.util.Arrays.copyOf(cr, n * 2);
        }
        cx[n] = x; cy[n] = y; cr[n] = r;
        n++;
    }

    public int size(){ return n; }

    /** outside every circle */
    public boolean safe(float x, float y){
        for(int i = 0; i < n; i++){
            float dx = x - cx[i], dy = y - cy[i];
            if(dx * dx + dy * dy < cr[i] * cr[i]) return false;
        }
        return true;
    }

    /** flying straight from a to b enters no circle; a circle a is already in may only be left */
    public boolean clearPath(float ax, float ay, float bx, float by){
        for(int i = 0; i < n; i++){
            float d = segmentDst(cx[i], cy[i], ax, ay, bx, by);
            float da = dst(cx[i], cy[i], ax, ay);
            if(da < cr[i]){
                if(d < da - leaveSlack) return false;
            }else if(d < cr[i]){
                return false;
            }
        }
        return true;
    }

    /**
     * The nearest spot within {@code range} of the work point (wx, wy) that is safe and reachable in a
     * straight flight from the drone (ux, uy).
     * @return false if there is none
     */
    public boolean workSpot(float ux, float uy, float wx, float wy, float range, float[] out){
        float base = (float)Math.atan2(uy - wy, ux - wx), best = Float.MAX_VALUE;
        for(float ring : workRings){
            float rr = range * ring;
            for(int k = 0; k < dirs; k++){
                double a = base + k * Math.PI * 2 / dirs;
                float px = wx + (float)Math.cos(a) * rr, py = wy + (float)Math.sin(a) * rr;
                if(!inBounds(px, py) || !safe(px, py) || !clearPath(ux, uy, px, py)) continue;
                float d = dst(ux, uy, px, py);
                if(d < best){
                    best = d;
                    out[0] = px;
                    out[1] = py;
                }
            }
        }
        return best < Float.MAX_VALUE;
    }

    /**
     * Where to flee: home (hx, hy; NaN = none) if it is safe and reachable, else the nearest safe
     * reachable point on growing rings around the drone (the one closest to home), else the nearest
     * safe point at all, else straight away from the nearest circle.
     */
    public Refuge refuge(float ux, float uy, float hx, float hy, float[] out){
        boolean home = !Float.isNaN(hx);
        if(home && safe(hx, hy) && clearPath(ux, uy, hx, hy)){
            out[0] = hx;
            out[1] = hy;
            return Refuge.home;
        }
        float unsafeBest = Float.MAX_VALUE, ux2 = 0, uy2 = 0;
        for(float r = refugeStep; r <= refugeMax; r += refugeStep){
            float best = Float.MAX_VALUE;
            for(int k = 0; k < dirs; k++){
                double a = k * Math.PI * 2 / dirs;
                float px = ux + (float)Math.cos(a) * r, py = uy + (float)Math.sin(a) * r;
                if(!inBounds(px, py) || !safe(px, py)) continue;
                if(!clearPath(ux, uy, px, py)){
                    if(r < unsafeBest){ unsafeBest = r; ux2 = px; uy2 = py; }
                    continue;
                }
                float score = home ? dst(px, py, hx, hy) : k;
                if(score < best){
                    best = score;
                    out[0] = px;
                    out[1] = py;
                }
            }
            if(best < Float.MAX_VALUE) return Refuge.ring;
        }
        if(unsafeBest < Float.MAX_VALUE){
            out[0] = ux2;
            out[1] = uy2;
            return Refuge.unsafePath;
        }
        // nothing safe within reach: away from the nearest circle's centre
        int near = -1;
        float nd = Float.MAX_VALUE;
        for(int i = 0; i < n; i++){
            float d = dst(ux, uy, cx[i], cy[i]) - cr[i];
            if(d < nd){ nd = d; near = i; }
        }
        float dx = near < 0 ? 1f : ux - cx[near], dy = near < 0 ? 0f : uy - cy[near], len = (float)Math.sqrt(dx * dx + dy * dy);
        if(len < 0.001f){ dx = 1f; dy = 0f; len = 1f; }
        out[0] = clampX(ux + dx / len * refugeMax);
        out[1] = clampY(uy + dy / len * refugeMax);
        return Refuge.away;
    }

    boolean inBounds(float x, float y){
        return (width <= 0 || (x >= 0 && x <= width)) && (height <= 0 || (y >= 0 && y <= height));
    }

    float clampX(float x){ return width <= 0 ? x : Math.max(0, Math.min(width, x)); }

    float clampY(float y){ return height <= 0 ? y : Math.max(0, Math.min(height, y)); }

    static float dst(float x1, float y1, float x2, float y2){
        float dx = x1 - x2, dy = y1 - y2;
        return (float)Math.sqrt(dx * dx + dy * dy);
    }

    /** distance from point p to the segment a-b */
    static float segmentDst(float px, float py, float ax, float ay, float bx, float by){
        float vx = bx - ax, vy = by - ay, l2 = vx * vx + vy * vy;
        float t = l2 <= 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (py - ay) * vy) / l2));
        return dst(px, py, ax + vx * t, ay + vy * t);
    }
}
