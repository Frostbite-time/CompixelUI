if (-not ('ComposeBenchmarkDesktop' -as [type])) { Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
public static class ComposeBenchmarkDesktop {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    struct STARTUPINFO {
        public int cb; public string reserved, desktop, title;
        public int x,y,w,h,xchars,ychars,fill,flags;
        public short show, reservedSize; public IntPtr reserved2,input,output,error;
    }
    [StructLayout(LayoutKind.Sequential)] struct PROCESS_INFORMATION {
        public IntPtr process,thread; public int processId,threadId;
    }
    [DllImport("user32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr CreateDesktopW(string name, IntPtr device, IntPtr devmode, int flags, uint access, IntPtr security);
    [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr desktop);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool CreateProcessW(string application, StringBuilder command, IntPtr processSecurity, IntPtr threadSecurity,
        bool inherit, uint flags, IntPtr environment, string cwd, ref STARTUPINFO startup, out PROCESS_INFORMATION process);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle, uint ms);
    [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    public static int Run(string shell, string command, string cwd, string name) {
        IntPtr desktop = CreateDesktopW(name, IntPtr.Zero, IntPtr.Zero, 0, 0x10000000, IntPtr.Zero);
        if (desktop == IntPtr.Zero) throw new Win32Exception();
        try {
            var startup = new STARTUPINFO { cb=Marshal.SizeOf<STARTUPINFO>(), desktop=name, flags=1, show=0 };
            PROCESS_INFORMATION process;
            if (!CreateProcessW(shell, new StringBuilder(command), IntPtr.Zero, IntPtr.Zero, false,
                0x08000000, IntPtr.Zero, cwd, ref startup, out process)) throw new Win32Exception();
            CloseHandle(process.thread);
            try {
                var elapsed = System.Diagnostics.Stopwatch.StartNew();
                while (WaitForSingleObject(process.process, 1000) == 258) {
                    if (elapsed.Elapsed.TotalMinutes > 15) {
                        System.Diagnostics.Process.GetProcessById(process.processId).Kill(true);
                        throw new TimeoutException("Background validation exceeded fifteen minutes");
                    }
                }
                uint code; GetExitCodeProcess(process.process, out code); return (int)code;
            } finally { CloseHandle(process.process); }
        } finally { CloseDesktop(desktop); }
    }
}
'@
}
