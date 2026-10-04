/*
 * Copyright 2026 thisisthepy and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads skiko the way an embedder does (Library.load, then a native object) and checks that
 * the process ends up with no libjawt mapped and no java.awt or javax.swing class loaded.
 * Run with -Xlog:class+load:file=<log> -Djava.awt.headless=true; the log is argument 0.
 * A class that is loaded is a superset of one that is initialised, so this is the stricter check.
 */
public class LoaderCheck {
    public static void main(String[] args) throws Exception {
        org.jetbrains.skiko.Library.INSTANCE.load();
        org.jetbrains.skia.Bitmap bitmap = new org.jetbrains.skia.Bitmap();
        bitmap.close();

        List<String> problems = new ArrayList<>();

        Path maps = Path.of("/proc/self/maps");
        if (Files.exists(maps)) {
            for (String line : Files.readAllLines(maps)) {
                if (line.contains("libjawt") || line.contains("libawt")) {
                    problems.add("native library mapped: " + line);
                }
            }
        }

        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.contains("java.awt.") || line.contains("javax.swing.") || line.contains("sun.awt.")) {
                problems.add("class loaded: " + line);
            }
        }

        if (!problems.isEmpty()) {
            problems.forEach(System.err::println);
            System.err.println("FAIL: skiko's loader touched AWT");
            System.exit(1);
        }
        System.out.println("OK: skiko loaded with no libjawt and no java.awt class (vm "
            + ManagementFactory.getRuntimeMXBean().getVmName() + ")");
    }
}
