import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import java.io.*;

public class DumpInfo extends GhidraScript {
    public void run() throws Exception {
        if (currentProgram == null) {
            println("no program loaded");
            return;
        }
        String outPath = getScriptArgs().length > 0 ? getScriptArgs()[0] : "/tmp/functions.txt";
        BufferedWriter w = new BufferedWriter(new FileWriter(outPath));
        Listing listing = currentProgram.getListing();
        FunctionIterator it = listing.getFunctions(true);
        int n = 0;
        while (it.hasNext()) {
            Function fn = it.next();
            w.write(fn.getName() + "\t" + fn.getEntryPoint().toString() + "\n");
            n++;
        }
        w.close();
        println("Dumped " + n + " functions to " + outPath);
    }
}
