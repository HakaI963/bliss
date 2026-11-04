import ghidra.app.script.GhidraScript;
import ghidra.app.services.Analyzer;
import ghidra.app.plugin.core.analysis.AutoAnalysisManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Disable the analyzers that make Ghidra eat a phone's memory.
 *
 * Run as a preScript, before analysis starts.
 *
 * The single biggest cost during import is Decompiler Parameter ID: it runs a
 * full decompilation of every function to infer signatures, and its working set
 * is many times the binary itself. On a phone that is the difference between a
 * bounded analysis and the OOM killer visiting the whole device.
 *
 * The trade is real and worth stating: without it, function signatures are less
 * precise — parameters are unnamed and return types are guessed. Structure,
 * names, addresses and call graphs are unaffected, which is what most analysis
 * actually needs. Pass "full" as the argument to keep everything.
 */
public class LowMemoryAnalysis extends GhidraScript {

    /** Analyzers that are safe to skip on a constrained device. */
    private static final String[] HEAVY = {
        "Decompiler Parameter ID",
        "Decompiler Switch Analysis",
        "Windows x86 PE RTTI Analyzer",
        "Demangler Microsoft",
    };

    @Override
    public void run() throws Exception {
        String mode = getScriptArgs().length > 0 ? getScriptArgs()[0] : "low";
        if ("full".equalsIgnoreCase(mode)) {
            println("LowMemoryAnalysis: full analysis requested, leaving analyzers on");
            return;
        }

        AutoAnalysisManager mgr = AutoAnalysisManager.getAnalysisManager(currentProgram);
        if (mgr == null) {
            println("LowMemoryAnalysis: no analysis manager, nothing to disable");
            return;
        }

        List<String> disabled = new ArrayList<>();
        for (String name : HEAVY) {
            for (Analyzer a : mgr.getAnalyzers()) {
                if (a.getName().equals(name)) {
                    mgr.setAnalyzerEnabled(name, false);
                    disabled.add(name);
                }
            }
        }
        println("LowMemoryAnalysis: disabled " + disabled.size() +
            " heavy analyzer(s): " + String.join(", ", disabled));
    }
}
