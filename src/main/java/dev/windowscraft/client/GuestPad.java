package dev.windowscraft.client;

import dev.windowscraft.net.Payloads;
import java.util.function.Supplier;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

/**
 * Your game controller, while you sit at someone else's screen: its whole state goes to the
 * owner's PC, where it is a controller of your own (ViGEmBus there) for whatever game reads
 * controllers — even while the owner plays Minecraft in front of it. Read each frame (GLFW wants
 * the main thread); sent when it changes (at most every 8 ms), and once a second anyway (the
 * owner's PC lets go of it when that stops: a lost connection never leaves a stick held).
 */
public final class GuestPad {
    /** Tests: a made-up controller instead of a real one (null: the real one). */
    public static volatile Supplier<Payloads.GamePad> testPad;
    /** Tests: the screen you sit at, for a test player who sat down by message (null: as you really sit). */
    public static volatile RemoteDisplays.Remote testSeat;

    private static final int[] BUTTONS = new int[15];
    static {
        // GLFW's gamepad buttons → XInput's bits
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_A] = 0x1000;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_B] = 0x2000;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_X] = 0x4000;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_Y] = 0x8000;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER] = 0x0100;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER] = 0x0200;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_BACK] = 0x0020;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_START] = 0x0010;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_GUIDE] = 0x0400;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_LEFT_THUMB] = 0x0040;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_THUMB] = 0x0080;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP] = 0x0001;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT] = 0x0008;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN] = 0x0002;
        BUTTONS[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT] = 0x0004;
    }

    private static GLFWGamepadState state;
    private static int jid = -1;
    private static long lookedAt, sentAt;
    private static RemoteDisplays.Remote sentTo;
    private static Payloads.GamePad last = Payloads.GamePad.NEUTRAL;

    /** Render thread, each frame: {@code seat} is the screen of someone else's you sit at (or null). */
    static void frame(RemoteDisplays.Remote seat) {
        if (testSeat != null) seat = testSeat;
        long now = System.nanoTime();
        if (seat != sentTo && sentTo != null) {
            // got up (or moved on): their controller lets go at once
            if (!last.equals(Payloads.GamePad.NEUTRAL)) send(sentTo, Payloads.GamePad.NEUTRAL);
            sentTo = null;
            last = Payloads.GamePad.NEUTRAL;
        }
        if (seat == null) return;
        Payloads.GamePad p = read(now);
        if (p == null) {
            if (sentTo != null && !last.equals(Payloads.GamePad.NEUTRAL)) send(seat, Payloads.GamePad.NEUTRAL); // (unplugged)
            last = Payloads.GamePad.NEUTRAL;
            return;
        }
        boolean changed = !same(p, last);
        if (changed && now - sentAt >= 8_000_000L || sentTo == seat && now - sentAt >= 1_000_000_000L || sentTo == null) {
            send(seat, p);
            last = p;
            sentTo = seat;
        }
    }

    private static void send(RemoteDisplays.Remote r, Payloads.GamePad p) {
        sentAt = System.nanoTime();
        if (dev.windowscraft.measure.Measure.ON && p.buttons() != last.buttons())
            dev.windowscraft.WindowsCraftClient.LOGGER.info("[pad] sent buttons {} at {}", Integer.toHexString(p.buttons()), sentAt);
        ClientNet.control(r.id, Payloads.OP_PAD, p.x(), p.y(), p.a(), p.f());
    }

    /** Changed enough to send: a button or trigger, or a stick moved more than a hair (noise stays home). */
    private static boolean same(Payloads.GamePad a, Payloads.GamePad b) {
        return a.buttons() == b.buttons() && a.leftTrigger() == b.leftTrigger() && a.rightTrigger() == b.rightTrigger()
                && Math.abs(a.lx() - b.lx()) < 0.004f && Math.abs(a.ly() - b.ly()) < 0.004f
                && Math.abs(a.rx() - b.rx()) < 0.004f && Math.abs(a.ry() - b.ry()) < 0.004f;
    }

    /** The first controller GLFW knows as a gamepad (looked for again every 2 s), or null. */
    private static Payloads.GamePad read(long now) {
        Supplier<Payloads.GamePad> test = testPad;
        if (test != null) return test.get();
        if (jid < 0 || now - lookedAt > 2_000_000_000L) {
            lookedAt = now;
            jid = -1;
            for (int j = GLFW.GLFW_JOYSTICK_1; j <= GLFW.GLFW_JOYSTICK_LAST; j++) {
                if (GLFW.glfwJoystickIsGamepad(j)) {
                    jid = j;
                    break;
                }
            }
        }
        if (jid < 0) return null;
        if (state == null) state = GLFWGamepadState.create();
        if (!GLFW.glfwGetGamepadState(jid, state)) {
            jid = -1;
            return null;
        }
        int buttons = 0;
        for (int b = 0; b < BUTTONS.length; b++) if (state.buttons(b) == GLFW.GLFW_PRESS) buttons |= BUTTONS[b];
        // (GLFW: triggers -1 released .. 1 pressed; sticks' Y down positive — XInput's is up)
        int lt = Math.round((state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) + 1f) / 2f * 255f);
        int rt = Math.round((state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) + 1f) / 2f * 255f);
        return new Payloads.GamePad(buttons, lt, rt, state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X), -state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y),
                state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X), -state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y));
    }

    private GuestPad() {}
}
