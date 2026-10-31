package autobuild.bootstrap.domain.shared;

/** Grid directions in the game's rotation order: 0 = +x, 1 = +y, 2 = -x, 3 = -y. */
public final class Dir{
    public static final int[] DX = {1, 0, -1, 0}, DY = {0, 1, 0, -1};

    private Dir(){}

    public static int opposite(int d){ return (d + 2) & 3; }
}
