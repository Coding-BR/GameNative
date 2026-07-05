package com.winlator.core;

import android.util.Log;

import app.gamenative.PrefManager;
import com.winlator.container.Container;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public final class RootPerformanceHelper {
    private static final String TAG = "RootPerformanceHelper";
    private static final long LAUNCH_OPTIMIZATION_WINDOW_MS = 30000;
    private static final long LAUNCH_OPTIMIZATION_INTERVAL_MS = 1000;
    private static final long SESSION_OPTIMIZATION_INTERVAL_MS = 5000;

    private RootPerformanceHelper() {}

    public static void requestRootAccessForSettings() {
        Thread worker = new Thread(() -> {
            Log.i(TAG, "Requesting root access for settings toggle");
            CommandResult rootCheck = runSuCommand("id", 3000);
            if (rootCheck.isSuccess() && rootCheck.output.contains("uid=0")) {
                Log.i(TAG, "Root access granted for settings toggle. " + rootCheck.summary());
            } else {
                Log.w(TAG, "Root access unavailable for settings toggle. " + rootCheck.summary());
            }
        }, "root-performance-request");
        worker.setDaemon(true);
        worker.start();
    }

    public static void applyForContainer(Container container, int mainPid) {
        if (container == null) {
            Log.d(TAG, "Root performance mode skipped: no container");
            return;
        }
        Profile profile = resolveProfile(container);
        if (profile == Profile.OFF) {
            Log.d(TAG, "Root performance profile disabled");
            return;
        }
        if (mainPid <= 0) {
            Log.w(TAG, "Root performance mode enabled but launch pid is invalid: " + mainPid);
            return;
        }
        if (isInstallerOrLauncherWorkload(container.getExecutablePath())) {
            Log.i(TAG, "Root performance skipped for installer/launcher workload: "
                    + container.getExecutablePath());
            return;
        }

        Thread worker = new Thread(() -> applyInternal(container, mainPid, profile), "root-performance-helper");
        worker.setDaemon(true);
        worker.start();
    }

    private static Profile resolveProfile(Container container) {
        String profile = null;
        try {
            if (container.hasRootPerformanceProfile()) {
                profile = container.getRootPerformanceProfile();
            }
            if (Container.ROOT_PERFORMANCE_GLOBAL.equals(profile) || profile == null) {
                profile = PrefManager.INSTANCE.getRootPerformanceProfile();
            }
        } catch (Throwable e) {
            Log.w(TAG, "Unable to read root performance profile", e);
        }
        return Profile.from(profile);
    }

    private static String getGlobalRootPerformanceProfile() {
        try {
            return PrefManager.INSTANCE.getRootPerformanceProfile();
        } catch (Throwable e) {
            Log.w(TAG, "Unable to read global root performance profile preference", e);
            return Container.ROOT_PERFORMANCE_OFF;
        }
    }

    private static void applyInternal(Container container, int mainPid, Profile profile) {
        Log.i(TAG, "Root performance profile " + profile.id + " enabled for pid " + mainPid);

        CommandResult rootCheck = runSuCommand("id", 3000);
        if (!rootCheck.isSuccess() || !rootCheck.output.contains("uid=0")) {
            Log.w(TAG, "Root unavailable; continuing without root optimizations. " + rootCheck.summary());
            return;
        }
        Log.i(TAG, "Root available; applying launch-time optimizations for profile " + profile.id);

        String cpuList = container.getCPUList(true);
        if (profile.atLeast(Profile.PERFORMANCE)) {
            String bigCores = detectHighPerformanceCpuList();
            if (bigCores != null && !bigCores.isEmpty()) {
                cpuList = bigCores;
                Log.i(TAG, "Using detected high-performance CPU list: " + cpuList);
            }
        }
        String affinityMask = buildAffinityMask(cpuList);
        if (affinityMask == null) {
            Log.w(TAG, "Invalid CPU affinity list, skipping taskset: " + cpuList);
        }
        String primeAffinityMask = null;
        if (profile == Profile.EXTREME) {
            String primeCpuList = detectPrimeCpuList();
            primeAffinityMask = buildAffinityMask(primeCpuList);
            if (primeAffinityMask != null) {
                Log.i(TAG, "Using prime CPU list for game executable: " + primeCpuList);
            }
        }

        if (profile.atLeast(Profile.PERFORMANCE)) {
            Log.i(TAG, "Skipping system-wide root performance writes for Android stability");
        }
        Set<Integer> pids = applyProcessOptimizationsForSession(
                mainPid,
                affinityMask,
                primeAffinityMask,
                container.getExecutablePath(),
                profile);
        Log.i(TAG, "Root performance profile " + profile.id + " applied to " + pids.size() + " process(es): " + pids);
    }

    private static Set<Integer> applyProcessOptimizationsForSession(
            int mainPid,
            String affinityMask,
            String primeAffinityMask,
            String executablePath,
            Profile profile) {
        Set<Integer> optimizedPids = new LinkedHashSet<>();
        long launchDeadline = System.currentTimeMillis() + LAUNCH_OPTIMIZATION_WINDOW_MS;

        while (new java.io.File("/proc/" + mainPid).exists()) {
            Set<Integer> pids = collectTargetPids(mainPid);
            for (int pid : pids) {
                if (optimizedPids.add(pid)) {
                    applyOptimizations(pid, affinityMask, primeAffinityMask, executablePath, profile);
                }
            }
            try {
                long interval = System.currentTimeMillis() < launchDeadline
                        ? LAUNCH_OPTIMIZATION_INTERVAL_MS
                        : SESSION_OPTIMIZATION_INTERVAL_MS;
                Thread.sleep(interval);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return optimizedPids;
    }

    private static Set<Integer> collectTargetPids(int mainPid) {
        Set<Integer> pids = new LinkedHashSet<>();
        pids.add(mainPid);

        for (ProcessHelper.ProcessInfo process : ProcessHelper.listSubProcesses()) {
            if (process.pid > 0 && isTargetProcess(process.name, readCmdline(process.pid))) pids.add(process.pid);
        }
        return pids;
    }

    private static boolean isTargetProcess(String name, String cmdline) {
        String lower = ((name == null ? "" : name) + " " + (cmdline == null ? "" : cmdline)).toLowerCase();
        if (isInstallerOrLauncherWorkload(lower)) return false;
        return lower.contains("wine")
                || lower.contains("wineserver")
                || lower.contains("box64")
                || lower.contains("box86")
                || lower.contains("fex")
                || lower.contains("fexloader")
                || lower.contains("pulseaudio")
                || lower.contains("steamclient_loader")
                || lower.contains("app.gamenative/files");
    }

    private static boolean isInstallerOrLauncherWorkload(String value) {
        if (value == null || value.isEmpty()) return false;
        String lower = value.replace('\\', '/').toLowerCase();
        return lower.contains("/installers/")
                || lower.contains("installer")
                || lower.contains("setup")
                || lower.contains("installshield")
                || lower.contains("vcredist")
                || lower.contains("dxsetup")
                || lower.contains("redist")
                || lower.contains("rockstar")
                || lower.contains("socialclub")
                || lower.contains("launcher")
                || lower.contains("updater")
                || lower.contains("update");
    }

    private static String readCmdline(int pid) {
        File file = new File("/proc/" + pid + "/cmdline");
        if (!file.isFile() || !file.canRead()) return "";
        StringBuilder value = new StringBuilder();
        try (FileReader reader = new FileReader(file)) {
            int ch;
            while ((ch = reader.read()) != -1) {
                value.append(ch == 0 ? ' ' : (char) ch);
                if (value.length() > 2048) break;
            }
        } catch (Exception ignored) {
            return "";
        }
        return value.toString();
    }

    private static String buildAffinityMask(String cpuList) {
        if (cpuList == null || !cpuList.matches("\\d+(,\\d+)*")) return null;

        try {
            return ProcessHelper.getAffinityMaskAsHexString(cpuList);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to parse CPU affinity list: " + cpuList, e);
            return null;
        }
    }

    private static void applyOptimizations(
            int pid,
            String affinityMask,
            String primeAffinityMask,
            String executablePath,
            Profile profile) {
        String cmdline = readCmdline(pid);
        String selectedAffinityMask = shouldPreferPrimeCores(cmdline, executablePath, profile)
                ? primeAffinityMask
                : affinityMask;

        StringBuilder command = new StringBuilder();
        command.append("opt_pid(){ p=\"$1\"; ");
        command.append("[ -d \"/proc/$p\" ] || return 0; ");
        command.append("renice -n -10 -p \"$p\" 2>&1; ");
        if (profile.atLeast(Profile.PERFORMANCE)) {
            command.append("if command -v ionice >/dev/null 2>&1; then ionice -c 1 -n 4 -p \"$p\" 2>&1; fi; ");
        }
        if (selectedAffinityMask != null && !selectedAffinityMask.isEmpty()) {
            command.append("taskset -p ").append(selectedAffinityMask).append(" \"$p\" 2>&1; ");
        }
        if (profile.atLeast(Profile.PERFORMANCE)) {
            command.append("if [ -w /dev/cpuctl/top-app/tasks ]; then echo \"$p\" > /dev/cpuctl/top-app/tasks 2>&1; fi; ");
            command.append("if [ -w /dev/cpuset/top-app/tasks ]; then echo \"$p\" > /dev/cpuset/top-app/tasks 2>&1; fi; ");
        }
        command.append("if [ -w \"/proc/$p/oom_score_adj\" ]; then echo -800 > \"/proc/$p/oom_score_adj\" 2>&1; fi; ");
        command.append("}; ");
        command.append("opt_pid ").append(pid).append("; ");
        command.append("[ -d /proc/").append(pid).append(" ] || exit 0; ");
        command.append("for t in /proc/").append(pid).append("/task/*; do ");
        command.append("[ -d \"$t\" ] || continue; tid=\"${t##*/}\"; opt_pid \"$tid\"; ");
        command.append("done");

        CommandResult result = runSuCommand(command.toString(), 5000);
        if (result.isSuccess()) {
            Log.d(TAG, "Applied " + profile.id + " root optimizations to pid " + pid + ". " + result.summary());
        } else {
            Log.w(TAG, "Failed to apply " + profile.id + " root optimizations to pid " + pid + ". " + result.summary());
        }
    }

    private static boolean shouldPreferPrimeCores(String cmdline, String executablePath, Profile profile) {
        if (profile != Profile.EXTREME || executablePath == null || executablePath.isEmpty()) return false;
        if (cmdline == null || cmdline.isEmpty()) return false;
        String executable = executablePath.replace('\\', '/');
        int slash = executable.lastIndexOf('/');
        if (slash >= 0) executable = executable.substring(slash + 1);
        if (executable.isEmpty()) return false;
        return cmdline.toLowerCase().contains(executable.toLowerCase());
    }

    private static void applySystemPerformanceProfile(int mainPid, Profile profile) {
        String snapshotPath = getSnapshotPath(mainPid);
        String command = ""
                + "SNAP='" + snapshotPath + "'; "
                + "rm -f \"$SNAP\"; touch \"$SNAP\" 2>/dev/null; "
                + "save_write(){ n=\"$1\"; v=\"$2\"; "
                + "if [ -e \"$n\" ] && [ -r \"$n\" ] && [ -w \"$n\" ]; then "
                + "old=\"$(cat \"$n\" 2>/dev/null)\"; "
                + "printf '%s\t%s\n' \"$n\" \"$old\" >> \"$SNAP\"; "
                + "echo \"$v\" > \"$n\" 2>/dev/null; "
                + "fi; }; "
                + "for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do save_write \"$g\" performance; done; "
                + "for d in /sys/devices/system/cpu/cpu*/cpufreq; do "
                + "if [ -r \"$d/scaling_max_freq\" ]; then max=\"$(cat \"$d/scaling_max_freq\" 2>/dev/null)\"; "
                + "save_write \"$d/scaling_min_freq\" \"$max\"; "
                + "fi; done; "
                + "for g in /sys/class/kgsl/kgsl-3d0/devfreq/governor /sys/class/devfreq/*kgsl*3d0*/governor; do save_write \"$g\" performance; done; "
                + "for d in /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl*3d0*; do "
                + "if [ -r \"$d/max_freq\" ]; then max=\"$(cat \"$d/max_freq\" 2>/dev/null)\"; "
                + "elif [ -r \"$d/available_frequencies\" ]; then max=\"$(tr ' ' '\\n' < \"$d/available_frequencies\" 2>/dev/null | grep -E '^[0-9]+$' | tail -n 1)\"; "
                + "else max=''; fi; "
                + "if [ -n \"$max\" ]; then save_write \"$d/min_freq\" \"$max\"; fi; "
                + "done; "
                + "if [ -r /sys/kernel/gpu/gpu_max_clock ]; then gpu_max=\"$(cat /sys/kernel/gpu/gpu_max_clock 2>/dev/null)\"; "
                + "elif [ -r /sys/kernel/gpu/gpu_freq_table ]; then gpu_max=\"$(tr ' ' '\\n' < /sys/kernel/gpu/gpu_freq_table 2>/dev/null | grep -E '^[0-9]+$' | head -n 1)\"; "
                + "else gpu_max=''; fi; "
                + "if [ -n \"$gpu_max\" ]; then save_write /sys/kernel/gpu/gpu_min_clock \"$gpu_max\"; fi; "
                + "save_write /proc/sys/vm/swappiness 60; "
                + "data_block=\"$(awk '$2 == \"/data\" { sub(\"/dev/block/\", \"\", $1); print $1; exit }' /proc/mounts 2>/dev/null)\"; "
                + "if [ -n \"$data_block\" ]; then save_write \"/sys/block/${data_block##*/}/queue/read_ahead_kb\" 1024; fi; "
                + "save_write /sys/class/kgsl/kgsl-3d0/force_clk_on 1; "
                + "save_write /sys/class/kgsl/kgsl-3d0/force_bus_on 1; "
                + "save_write /sys/class/kgsl/kgsl-3d0/force_rail_on 1; "
                + "save_write /sys/class/kgsl/kgsl-3d0/idle_timer 10000; "
                + "save_write /dev/stune/top-app/schedtune.boost 15; "
                + "save_write /dev/stune/top-app/schedtune.prefer_idle 1; "
                + "save_write /dev/cpuctl/top-app/cpu.uclamp.min 80; "
                + "save_write /dev/cpuctl/top-app/cpu.uclamp.max 100; "
                + "save_write /dev/cpuctl/top-app/cpu.uclamp.latency_sensitive 1; "
                + "save_write /proc/sys/kernel/sched_boost 1; "
                + (profile == Profile.EXTREME ? getExtremePerformanceCommand() : "")
                + "wc -l \"$SNAP\" 2>/dev/null";

        CommandResult result = runSuCommand(command, 7000);
        if (result.isSuccess()) {
            Log.i(TAG, "Applied system root performance profile " + profile.id + ". " + result.summary());
        } else {
            Log.w(TAG, "System root performance profile partially failed. " + result.summary());
        }
    }

    private static void restoreSystemPerformanceProfile(int mainPid) {
        String snapshotPath = getSnapshotPath(mainPid);
        String command = ""
                + "SNAP='" + snapshotPath + "'; "
                + "if [ -r \"$SNAP\" ]; then "
                + "while read -r n v; do "
                + "if [ \"$n\" = \"__cmd_power_fixed_performance\" ]; then cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1; continue; fi; "
                + "if [ -n \"$n\" ] && [ -w \"$n\" ]; then echo \"$v\" > \"$n\" 2>/dev/null; fi; "
                + "done < \"$SNAP\"; "
                + "rm -f \"$SNAP\"; "
                + "fi";

        CommandResult result = runSuCommand(command, 7000);
        if (result.isSuccess()) {
            Log.i(TAG, "Restored system root performance profile for pid " + mainPid);
        } else {
            Log.w(TAG, "Failed to restore system root performance profile for pid " + mainPid + ". " + result.summary());
        }
    }

    private static String getSnapshotPath(int mainPid) {
        return "/data/local/tmp/gamenative_root_perf_" + mainPid + ".snapshot";
    }

    private static String detectHighPerformanceCpuList() {
        File cpuRoot = new File("/sys/devices/system/cpu");
        File[] cpus = cpuRoot.listFiles((dir, name) -> name.matches("cpu\\d+"));
        if (cpus == null || cpus.length == 0) return null;

        long maxFreq = -1;
        TreeMap<Integer, Long> cpuFreqs = new TreeMap<>();
        for (File cpu : cpus) {
            int cpuIndex = parseCpuIndex(cpu.getName());
            if (cpuIndex < 0) continue;
            long freq = readLong(new File(cpu, "cpufreq/scaling_max_freq"));
            if (freq <= 0) freq = readLong(new File(cpu, "cpufreq/cpuinfo_max_freq"));
            if (freq <= 0) continue;
            if (freq > maxFreq) maxFreq = freq;
            cpuFreqs.put(cpuIndex, freq);
        }

        LinkedHashSet<Integer> highPerformanceCpus = new LinkedHashSet<>();
        long minimumBigCoreFreq = (maxFreq * 75) / 100;
        for (Map.Entry<Integer, Long> entry : cpuFreqs.entrySet()) {
            if (entry.getValue() >= minimumBigCoreFreq) highPerformanceCpus.add(entry.getKey());
        }
        if (highPerformanceCpus.isEmpty()) return null;
        StringBuilder list = new StringBuilder();
        for (Integer cpu : highPerformanceCpus) {
            if (list.length() > 0) list.append(',');
            list.append(cpu);
        }
        return list.toString();
    }

    private static String detectPrimeCpuList() {
        File cpuRoot = new File("/sys/devices/system/cpu");
        File[] cpus = cpuRoot.listFiles((dir, name) -> name.matches("cpu\\d+"));
        if (cpus == null || cpus.length == 0) return null;

        long maxFreq = -1;
        TreeMap<Integer, Long> cpuFreqs = new TreeMap<>();
        for (File cpu : cpus) {
            int cpuIndex = parseCpuIndex(cpu.getName());
            if (cpuIndex < 0) continue;
            long freq = readLong(new File(cpu, "cpufreq/scaling_max_freq"));
            if (freq <= 0) freq = readLong(new File(cpu, "cpufreq/cpuinfo_max_freq"));
            if (freq <= 0) continue;
            if (freq > maxFreq) maxFreq = freq;
            cpuFreqs.put(cpuIndex, freq);
        }

        if (maxFreq <= 0) return null;
        StringBuilder list = new StringBuilder();
        for (Map.Entry<Integer, Long> entry : cpuFreqs.entrySet()) {
            if (entry.getValue() == maxFreq) {
                if (list.length() > 0) list.append(',');
                list.append(entry.getKey());
            }
        }
        return list.length() > 0 ? list.toString() : null;
    }

    private static int parseCpuIndex(String cpuName) {
        try {
            return Integer.parseInt(cpuName.substring(3));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static long readLong(File file) {
        if (!file.isFile() || !file.canRead()) return -1;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String value = reader.readLine();
            return value == null ? -1 : Long.parseLong(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String getExtremePerformanceCommand() {
        return ""
                + "cmd power set-fixed-performance-mode-enabled true >/dev/null 2>&1 && printf '%s\t%s\n' __cmd_power_fixed_performance false >> \"$SNAP\"; "
                + "save_write /sys/class/kgsl/kgsl-3d0/force_no_nap 1; "
                + "save_write /sys/class/kgsl/kgsl-3d0/bus_split 0; "
                + "save_write /sys/module/adreno_idler/parameters/adreno_idler_active 0; ";
    }

    private static CommandResult runSuCommand(String command, long timeoutMs) {
        StringBuilder output = new StringBuilder();
        int exitCode = -1;
        boolean timedOut = false;
        java.lang.Process process = null;

        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) output.append('\n');
                    output.append(line);
                }
            }

            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue();
            } else {
                timedOut = true;
                process.destroyForcibly();
            }
        } catch (Exception e) {
            if (output.length() > 0) output.append('\n');
            output.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }

        return new CommandResult(exitCode, timedOut, output.toString().trim());
    }

    private static final class CommandResult {
        private final int exitCode;
        private final boolean timedOut;
        private final String output;

        private CommandResult(int exitCode, boolean timedOut, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output == null ? "" : output;
        }

        private boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }

        private String summary() {
            String text = output.length() > 240 ? output.substring(0, 240) + "..." : output;
            return "exitCode=" + exitCode + ", timedOut=" + timedOut + ", output=" + text;
        }
    }

    private enum Profile {
        OFF(Container.ROOT_PERFORMANCE_OFF, 0),
        SAFE(Container.ROOT_PERFORMANCE_SAFE, 1),
        PERFORMANCE(Container.ROOT_PERFORMANCE_PERFORMANCE, 2),
        EXTREME(Container.ROOT_PERFORMANCE_EXTREME, 3);

        private final String id;
        private final int level;

        Profile(String id, int level) {
            this.id = id;
            this.level = level;
        }

        private boolean atLeast(Profile other) {
            return level >= other.level;
        }

        private static Profile from(String profile) {
            String normalized = Container.normalizeRootPerformanceProfile(profile);
            if (Container.ROOT_PERFORMANCE_SAFE.equals(normalized)) return SAFE;
            if (Container.ROOT_PERFORMANCE_PERFORMANCE.equals(normalized)) return PERFORMANCE;
            if (Container.ROOT_PERFORMANCE_EXTREME.equals(normalized)) return EXTREME;
            return OFF;
        }
    }
}
