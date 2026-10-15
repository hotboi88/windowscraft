# A stand-in game on SDL (as many indie and ported games are): prints each A press it gets.
import ctypes, os, sys, time
title, x, y, bg = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
os.environ["PYGAME_HIDE_SUPPORT_PROMPT"] = "1"
os.environ["SDL_VIDEO_WINDOW_POS"] = "%s,%s" % (x, y)
os.environ["SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS"] = bg  # SDL's default is 0: no controller input while not in front
import pygame
pygame.init()
screen = pygame.display.set_mode((360, 200))
pygame.display.set_caption(title)
hwnd = pygame.display.get_wm_info()["window"]
pygame.joystick.init()
sticks = {}
print("ready sdl bg=%s hwnd %d" % (bg, hwnd), flush=True)
held = False
while True:
    for e in pygame.event.get():
        if e.type == pygame.QUIT:
            sys.exit(0)
        if e.type == pygame.JOYDEVICEADDED:
            j = pygame.joystick.Joystick(e.device_index)
            sticks[j.get_instance_id()] = j
        if e.type in (pygame.JOYBUTTONDOWN, pygame.JOYBUTTONUP) and e.button == 0:
            down = e.type == pygame.JOYBUTTONDOWN
            if down != held:
                held = down
                fg = int(ctypes.windll.user32.GetForegroundWindow() == hwnd)
                print("A %s %d fg=%d" % ("down" if down else "up", time.perf_counter_ns(), fg), flush=True)
    time.sleep(0.001)
