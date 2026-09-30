package io.flowcatalyst.eventloop;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/// A javac plugin that fails the compilation when code running on a Vert.x event loop makes a
/// blocking call (`docs/spec/eventloop-check.md`). Vert.x's own blocked-thread warning finds this
/// only at runtime, and only on the paths a run happens to take.
///
/// Enable it with `-Xplugin:EventLoopCheck -XDcompilePolicy=simple`. The plugin must be on the
/// processor path or, when none is set, the class path.
///
/// **Why the compile policy:** the check follows calls across files, so every file must be
/// attributed before it runs. The `simple` policy guarantees that: javac attributes the whole
/// compilation before flow analysis, and an error reported at the first `ANALYZE` event stops it
/// before any class file is written. As a defence against another policy, the check fails the
/// compilation if it finds an unattributed file rather than pass with part of the code unchecked.
/// JDK 25 attributes everything first under every policy, so that guard has no test that can
/// provoke it.
public final class EventLoopCheck implements Plugin {

    public static final String NAME = "EventLoopCheck";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        // Keyed by source file: an annotation-processing round re-enters the same files, and the
        // final round's trees are the ones javac attributes.
        Map<URI, CompilationUnitTree> units = new LinkedHashMap<>();
        task.addTaskListener(new TaskListener() {
            private boolean analysed;

            @Override
            public void finished(TaskEvent e) {
                if (e.getKind() == TaskEvent.Kind.ENTER && e.getCompilationUnit() != null) {
                    units.put(e.getCompilationUnit().getSourceFile().toUri(), e.getCompilationUnit());
                } else if (e.getKind() == TaskEvent.Kind.ANALYZE && !analysed) {
                    analysed = true;
                    new LoopAnalysis(task, units.values()).run();
                }
            }
        });
    }
}
