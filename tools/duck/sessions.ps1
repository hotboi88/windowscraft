# Lists audio sessions (pid, state, volume) on every active output device, or sets one app's
# session volume:  sessions.ps1 [-Pid <pid>] [-Set <0..1>]
param([int]$TargetPid = 0, [double]$Set = -1)
Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCo {}
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
  int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
}
[Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceCollection { int GetCount(out int n); int Item(int i, out IMMDevice d); }
[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
  int Activate(ref Guid iid, int ctx, IntPtr p, [MarshalAs(UnmanagedType.IUnknown)] out object o);
}
[Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioSessionManager2 {
  int GetAudioSessionControl(IntPtr a, int b, out IntPtr c);
  int GetSimpleAudioVolume(IntPtr a, int b, out IntPtr c);
  int GetSessionEnumerator(out IAudioSessionEnumerator e);
}
[Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioSessionEnumerator { int GetCount(out int n); int GetSession(int i, out IAudioSessionControl2 s); }
[Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioSessionControl2 {
  int GetState(out int s);
  int GetDisplayName(out IntPtr a); int SetDisplayName(IntPtr a, IntPtr b);
  int GetIconPath(out IntPtr a); int SetIconPath(IntPtr a, IntPtr b);
  int GetGroupingParam(out Guid g); int SetGroupingParam(IntPtr a, IntPtr b);
  int RegisterAudioSessionNotification(IntPtr a); int UnregisterAudioSessionNotification(IntPtr a);
  int GetSessionIdentifier(out IntPtr a); int GetSessionInstanceIdentifier(out IntPtr a);
  int GetProcessId(out int pid);
}
[Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface ISimpleAudioVolume {
  int SetMasterVolume(float v, IntPtr ctx); int GetMasterVolume(out float v);
}
public static class Sessions {
  public static List<string> Run(int pid, double set) {
    var outp = new List<string>();
    var en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCo();
    IMMDeviceCollection devs; en.EnumAudioEndpoints(0, 1, out devs);
    int n; devs.GetCount(out n);
    for (int i = 0; i < n; i++) {
      IMMDevice d; devs.Item(i, out d);
      Guid iid = typeof(IAudioSessionManager2).GUID; object o;
      if (d.Activate(ref iid, 23, IntPtr.Zero, out o) != 0) continue;
      IAudioSessionEnumerator se; ((IAudioSessionManager2)o).GetSessionEnumerator(out se);
      int m; se.GetCount(out m);
      for (int j = 0; j < m; j++) {
        IAudioSessionControl2 c; se.GetSession(j, out c);
        int p, st; c.GetProcessId(out p); c.GetState(out st);
        var v = (ISimpleAudioVolume)c; float vol; v.GetMasterVolume(out vol);
        if (pid != 0 && p != pid) continue;
        if (set >= 0) { v.SetMasterVolume((float)set, IntPtr.Zero); v.GetMasterVolume(out vol); }
        outp.Add(String.Format("device {0} pid {1} state {2} volume {3:0.0000}", i, p, st, vol));
      }
    }
    return outp;
  }
}
"@
[Sessions]::Run($TargetPid, $Set)
