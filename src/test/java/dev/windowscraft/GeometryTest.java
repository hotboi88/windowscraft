package dev.windowscraft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.display.WallRect;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

class GeometryTest {
    @Test
    void outputSizeNeverUpscalesAndKeepsAspect() {
        assertArrayEquals(new int[]{1280, 720}, new CaptureSettings(720, 30, true).outputSize(2560, 1440));
        assertArrayEquals(new int[]{1920, 1080}, new CaptureSettings(1080, 30, true).outputSize(1920, 1080));
        assertArrayEquals(new int[]{800, 600}, new CaptureSettings(1080, 30, true).outputSize(800, 600));
        assertArrayEquals(new int[]{3440, 1440}, new CaptureSettings(0, 30, true).outputSize(3440, 1440));
        assertEquals(0, new CaptureSettings(720, 30, true).outputSize(1366, 1025)[0] % 2);
    }

    @Test
    void autoFitFillsNearMatchesAndLetterboxesRealMismatches() {
        int auto = CaptureSettings.FIT_AUTO;
        assertTrue(CaptureSettings.shouldFill(auto, 1920, 1080, 7, 4), "16:9 on 7×4 (1.6% off) fills: no hairline bars");
        assertTrue(CaptureSettings.shouldFill(auto, 1920, 1080, 16, 9), "exact match fills");
        assertTrue(!CaptureSettings.shouldFill(auto, 1920, 1080, 6, 3), "16:9 on 2:1 (12.5% off) keeps its shape");
        assertTrue(!CaptureSettings.shouldFill(auto, 1080, 1920, 7, 4), "a phone-shaped window keeps its shape");
        assertTrue(CaptureSettings.shouldFill(CaptureSettings.FIT_FILL, 1080, 1920, 7, 4), "Fill always fills");
        assertTrue(!CaptureSettings.shouldFill(CaptureSettings.FIT_BARS, 1920, 1080, 16, 9), "Fit never stretches");
    }

    @Test
    void southFacingWallReadsLeftToRight() {
        // wall at z=-8 facing south: the viewer looks north, so their right is east (+X)
        WallRect r = new WallRect(Direction.SOUTH, new BlockPos(-3, 0, -8), new BlockPos(3, 3, -8));
        assertEquals(7, r.widthBlocks());
        assertEquals(4, r.heightBlocks());
        Vec3d[] c = r.corners(1, 1);
        assertTrue(c[0].x < c[1].x, "top-left is west of top-right");
        assertEquals(4.0, c[0].y, 1e-9);
        assertEquals(-7 + WallRect.SURFACE_OFFSET, c[0].z, 1e-9);
    }

    @Test
    void westFacingWallIsPillarboxed() {
        WallRect r = new WallRect(Direction.WEST, new BlockPos(9, 0, -7), new BlockPos(9, 2, -2));
        assertEquals(6, r.widthBlocks());
        Vec3d[] c = r.corners(16, 9);
        double w = Math.abs(c[1].z - c[0].z), h = c[0].y - c[3].y;
        assertEquals(16 / 9.0, w / h, 1e-6);
        assertEquals(3.0, h, 1e-9);
        assertTrue(c[0].z < c[1].z, "a viewer facing east has south (+Z) on their right");
        assertEquals(9 - WallRect.SURFACE_OFFSET, c[0].x, 1e-9);
    }

    @Test
    void monitorBlockScreenFacesItsTurnAndReadsLeftToRight() {
        for (int t = 0; t < 8; t++) {
            WallRect r = WallRect.monitor(new BlockPos(10, 64, -5), t);
            Vec3d n = r.normal(), right = r.rightVector();
            Vec3d[] c = r.corners(1, 1);
            Vec3d across = c[1].subtract(c[0]), down = c[3].subtract(c[0]);
            assertEquals(1, n.length(), 1e-9);
            assertEquals(0, across.dotProduct(n), 1e-9, "the screen is flat across its facing");
            assertTrue(across.normalize().dotProduct(right) > 0.999, "top-left to top-right runs to the viewer's right (turn " + t + ")");
            assertTrue(down.y < 0 && Math.abs(down.x) < 1e-9 && Math.abs(down.z) < 1e-9, "and top to bottom goes down");
            // seen from in front, the screen's front is toward you: (right × down) points back at the viewer
            Vec3d out = across.crossProduct(down).normalize();
            assertTrue(out.dotProduct(n) < -0.999 || out.dotProduct(n) > 0.999);
            assertEquals(r.width(), across.length(), 1e-9);
            assertEquals(r.height(), -down.y, 1e-9);
            // inside its own block
            for (Vec3d p : c) assertTrue(p.x > 10 && p.x < 11 && p.z > -5 && p.z < -4 && p.y > 64 && p.y < 65, "corner " + p);
            assertEquals(r, WallRect.parse(r.faceString(), r.min(), r.max()), "the face string goes both ways");
        }
        // turn 0 faces south (+z), 2 west, 4 north, 6 east
        assertEquals(1, WallRect.monitor(BlockPos.ORIGIN, 0).normal().z, 1e-9);
        assertEquals(-1, WallRect.monitor(BlockPos.ORIGIN, 2).normal().x, 1e-9);
        assertEquals(-1, WallRect.monitor(BlockPos.ORIGIN, 4).normal().z, 1e-9);
        assertEquals(1, WallRect.monitor(BlockPos.ORIGIN, 6).normal().x, 1e-9);
        // facing south, you look north at it: its right is east, like a south-facing wall
        assertEquals(1, WallRect.monitor(BlockPos.ORIGIN, 0).rightVector().x, 1e-9);
        assertEquals(new WallRect(Direction.SOUTH, BlockPos.ORIGIN, BlockPos.ORIGIN).rightVector(), WallRect.monitor(BlockPos.ORIGIN, 0).rightVector());
        // walls parse as before; nonsense doesn't
        assertEquals(new WallRect(Direction.EAST, BlockPos.ORIGIN, new BlockPos(0, 2, 3)), WallRect.parse("east", BlockPos.ORIGIN, new BlockPos(0, 2, 3)));
        assertEquals(null, WallRect.parse("up", BlockPos.ORIGIN, BlockPos.ORIGIN));
        assertEquals(null, WallRect.parse("north@9", BlockPos.ORIGIN, BlockPos.ORIGIN));
        assertEquals(null, WallRect.parse("north@3", BlockPos.ORIGIN, new BlockPos(1, 0, 0)));
        // a monitor never overlaps a wall, and two monitors only on the same block
        assertTrue(!WallRect.monitor(BlockPos.ORIGIN, 0).overlaps(new WallRect(Direction.SOUTH, new BlockPos(-1, -1, 0), new BlockPos(1, 1, 0))));
        assertTrue(WallRect.monitor(BlockPos.ORIGIN, 0).overlaps(WallRect.monitor(BlockPos.ORIGIN, 3)));
    }

    @Test
    void aPosedMonitorStandingStraightIsTheSameAsTheBlockModel() {
        for (int t = 0; t < 8; t++) {
            BlockPos at = new BlockPos(3, 70, -2);
            WallRect block = WallRect.monitor(at, t), posed = WallRect.posed(at, dev.windowscraft.display.MonitorPose.standing(t));
            Vec3d[] a = block.corners(16, 9), b = posed.corners(16, 9);
            for (int i = 0; i < 4; i++) assertTrue(a[i].distanceTo(b[i]) < 1e-9, "turn " + t + " corner " + i + ": " + a[i] + " vs " + b[i]);
            assertEquals(t, posed.turn());
            assertTrue(block.normal().distanceTo(posed.normal()) < 1e-9);
            WallRect back = WallRect.parse(posed.faceString(), at, at);
            assertTrue(back != null && back.pose().near(posed.pose()), "a posed monitor's face string goes both ways: " + posed.faceString());
            assertTrue(posed.faceString().length() <= 128, "fits the network message");
        }
    }

    @Test
    void joinedMonitorsHingeOnTheEdgeAndFaceYou() {
        BlockPos main = new BlockPos(0, 64, 0);
        var stand = dev.windowscraft.display.MonitorPose.standing(0); // facing south (+z)
        Vec3d eye = new Vec3d(0.5, 64.6, 3.5);
        var right = dev.windowscraft.display.MonitorPose.join(stand, main, main.east(), eye, false);
        var flat = dev.windowscraft.display.MonitorPose.join(stand, main, main.east(), eye, true);
        var top = dev.windowscraft.display.MonitorPose.join(stand, main, main.up(), new Vec3d(0.5, 64.6, 2.5), false);
        assertTrue(right != null && flat != null && top != null);
        assertTrue(right.normal().x < -0.1, "the right one turns in toward you: " + right.normal());
        assertEquals(1, flat.normal().z, 1e-9, "sneaking keeps it flat");
        assertTrue(top.normal().y < -0.1, "the top one tilts down toward you: " + top.normal());
        // the hinge: the main screen's right front edge is the right one's left front edge
        Vec3d a = stand.at(main, dev.windowscraft.display.MonitorPose.HALF_W, 0, dev.windowscraft.display.MonitorPose.FRONT);
        Vec3d b = right.at(main.east(), -dev.windowscraft.display.MonitorPose.HALF_W, 0, dev.windowscraft.display.MonitorPose.FRONT);
        assertTrue(a.distanceTo(b) < 1e-6, "edges meet: " + a + " / " + b);
        Vec3d c = stand.at(main, 0, dev.windowscraft.display.MonitorPose.HALF_H, dev.windowscraft.display.MonitorPose.FRONT);
        Vec3d d = top.at(main.up(), 0, -dev.windowscraft.display.MonitorPose.HALF_H, dev.windowscraft.display.MonitorPose.FRONT);
        assertTrue(c.distanceTo(d) < 1e-6, "top and bottom edges meet: " + c + " / " + d);
        // in front of it, a block away: not joined (a monitor of its own)
        assertTrue(dev.windowscraft.display.MonitorPose.join(stand, main, main.south(), eye, false) == null);
        // and the flat one is the main one moved one block along
        Vec3d[] m = WallRect.posed(main, stand).corners(1, 1), f = WallRect.posed(main.east(), flat).corners(1, 1);
        assertEquals(1, f[0].x - m[0].x, 1e-9);
    }
}
