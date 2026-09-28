package io.flowcatalyst.eventloop;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/// The check itself (`docs/spec/eventloop-check.md` §1).
///
/// **Roots**, the code that runs on an event loop:
/// - a lambda or method reference whose target type is a Vert.x type;
/// - a lambda or method reference passed to a Vert.x method (`executeBlocking` excepted), which
///   covers the `java.util.function` arguments of `Future.map`, `compose` and friends;
/// - a lambda passed for an [OnEventLoop] parameter;
/// - a method overriding a Vert.x method, or marked [OnEventLoop].
///
/// **Reach:** from each root, every call whose target has source in this compilation is followed,
/// transitively. A lambda nested in loop code is loop code too, since `forEach` runs it in place.
/// The exception is a lambda handed to another thread: JDK executors and thread builders, `Thread`
/// constructors, `CompletableFuture`'s `…Async` methods, Vert.x `executeBlocking`, or an
/// [OffEventLoop] parameter.
///
/// **Limits:** a call through an interface or abstract method is not followed (its implementation
/// is unknown), and neither is code outside this compilation. Both are still checked against the
/// denylist at the call site.
final class LoopAnalysis {

    private static final String VERTX = "io.vertx.";
    private static final String ON_LOOP = OnEventLoop.class.getName();
    private static final String OFF_LOOP = OffEventLoop.class.getName();

    private record Work(TreePath body, List<String> chain) {
    }

    /// The call a lambda or method reference is an argument of.
    private record Call(ExecutableElement method, int argument, boolean constructor) {
    }

    private final Trees trees;
    private final Types types;
    private final Elements elements;
    private final BlockingCalls blocking;
    private final Collection<CompilationUnitTree> units;
    private final Deque<Work> work = new ArrayDeque<>();
    private final Set<Element> followed = new HashSet<>();
    private final Set<Tree> reported = Collections.newSetFromMap(new IdentityHashMap<>());
    private final TypeMirror executor;
    private final TypeMirror threadBuilder;
    private final TypeMirror threadFactory;
    private final TypeMirror thread;
    private final TypeMirror completableFuture;
    private final TypeMirror timer;
    private CompilationUnitTree unattributed;

    LoopAnalysis(JavacTask task, Collection<CompilationUnitTree> units) {
        this.trees = Trees.instance(task);
        this.types = task.getTypes();
        this.elements = task.getElements();
        this.blocking = new BlockingCalls(types, elements);
        this.units = List.copyOf(units);
        this.executor = type("java.util.concurrent.Executor");
        this.threadBuilder = type("java.lang.Thread.Builder");
        this.threadFactory = type("java.util.concurrent.ThreadFactory");
        this.thread = type("java.lang.Thread");
        this.completableFuture = type("java.util.concurrent.CompletableFuture");
        this.timer = type("java.util.Timer");
    }

    void run() {
        for (CompilationUnitTree cu : units) {
            new RootFinder().scan(cu, null);
            if (unattributed != null) {
                trees.printMessage(Diagnostic.Kind.ERROR, "[" + EventLoopCheck.NAME + "] "
                        + fileName(unattributed) + " was not attributed when the check ran; compile with "
                        + "-XDcompilePolicy=simple so the whole compilation is checked", unattributed, unattributed);
                return;
            }
        }
        while (!work.isEmpty()) {
            Work w = work.poll();
            new LoopScanner(w.chain()).scan(w.body(), null);
        }
    }

    // ── roots ────────────────────────────────────────────────────────────────

    private final class RootFinder extends TreePathScanner<Void, Void> {
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void v) {
            if (unattributed == null && trees.getElement(getCurrentPath()) == null) {
                unattributed = getCurrentPath().getCompilationUnit();
            }
            return super.visitMethodInvocation(node, v);
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree node, Void v) {
            TreePath path = getCurrentPath();
            if (isRoot(path)) {
                work.add(new Work(new TreePath(path, node.getBody()), List.of("the Vert.x callback at " + where(path))));
            }
            return super.visitLambdaExpression(node, v);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void v) {
            TreePath path = getCurrentPath();
            if (isRoot(path) && trees.getElement(path) instanceof ExecutableElement m) {
                follow(m, List.of("the Vert.x callback " + node + " at " + where(path)));
            }
            return super.visitMemberReference(node, v);
        }

        @Override
        public Void visitMethod(MethodTree node, Void v) {
            if (node.getBody() != null && trees.getElement(getCurrentPath()) instanceof ExecutableElement m) {
                if (annotated(m, ON_LOOP)) {
                    follow(m, List.of(describe(m) + ", marked @OnEventLoop"));
                } else if (overridesVertx(m)) {
                    follow(m, List.of(describe(m) + ", which overrides a Vert.x method"));
                }
            }
            return super.visitMethod(node, v);
        }
    }

    private boolean isRoot(TreePath functional) {
        if (isOffLoop(functional)) return false;
        if (isVertx(trees.getTypeMirror(functional))) return true;
        Call call = enclosingCall(functional);
        if (call == null) return false;
        return (isVertx(call.method()) && !call.method().getSimpleName().contentEquals("executeBlocking"))
                || annotated(parameter(call), ON_LOOP);
    }

    private boolean isOffLoop(TreePath functional) {
        Call call = enclosingCall(functional);
        if (call == null) return false;
        ExecutableElement m = call.method();
        String name = m.getSimpleName().toString();
        TypeMirror owner = types.erasure(m.getEnclosingElement().asType());
        if (annotated(parameter(call), OFF_LOOP)) return true;
        if (isVertx(m) && name.equals("executeBlocking")) return true;
        if (subtype(owner, executor) || subtype(owner, threadBuilder) || subtype(owner, threadFactory)) return true;
        if (subtype(owner, thread) && (call.constructor() || name.equals("startVirtualThread"))) return true;
        if (subtype(owner, completableFuture) && name.endsWith("Async")) return true;
        return subtype(owner, timer) && name.startsWith("schedule");
    }

    /// The call `functional` is passed to, looking through parentheses and casts.
    private Call enclosingCall(TreePath functional) {
        Tree child = functional.getLeaf();
        TreePath parent = functional.getParentPath();
        while (parent != null && (parent.getLeaf() instanceof ParenthesizedTree || parent.getLeaf() instanceof TypeCastTree)) {
            child = parent.getLeaf();
            parent = parent.getParentPath();
        }
        if (parent == null) return null;
        List<? extends ExpressionTree> args;
        boolean constructor;
        if (parent.getLeaf() instanceof MethodInvocationTree mi) {
            args = mi.getArguments();
            constructor = false;
        } else if (parent.getLeaf() instanceof NewClassTree nc) {
            args = nc.getArguments();
            constructor = true;
        } else {
            return null;
        }
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) == child && trees.getElement(parent) instanceof ExecutableElement m) {
                return new Call(m, i, constructor);
            }
        }
        return null;
    }

    private static VariableElement parameter(Call call) {
        List<? extends VariableElement> params = call.method().getParameters();
        if (params.isEmpty()) return null;
        return params.get(Math.min(call.argument(), params.size() - 1));
    }

    private boolean overridesVertx(ExecutableElement m) {
        if (!(m.getEnclosingElement() instanceof TypeElement owner)) return false;
        return overridesVertx(m, owner, owner.asType(), new HashSet<>());
    }

    private boolean overridesVertx(ExecutableElement m, TypeElement owner, TypeMirror type, Set<String> seen) {
        for (TypeMirror st : types.directSupertypes(type)) {
            if (!(st instanceof DeclaredType dt) || !(dt.asElement() instanceof TypeElement ste)) continue;
            if (!seen.add(ste.getQualifiedName().toString())) continue;
            if (ste.getQualifiedName().toString().startsWith(VERTX)) {
                for (Element member : ste.getEnclosedElements()) {
                    if (member instanceof ExecutableElement candidate
                            && candidate.getSimpleName().equals(m.getSimpleName())
                            && elements.overrides(m, candidate, owner)) {
                        return true;
                    }
                }
            }
            if (overridesVertx(m, owner, st, seen)) return true;
        }
        return false;
    }

    // ── reach ────────────────────────────────────────────────────────────────

    /// Scans one root's body, or one followed method's, reporting blocking calls and queueing the
    /// source methods it calls.
    private final class LoopScanner extends TreePathScanner<Void, Void> {
        private final List<String> chain;

        LoopScanner(List<String> chain) {
            this.chain = chain;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void v) {
            if (trees.getElement(getCurrentPath()) instanceof ExecutableElement m) {
                blocking.method(m, receiverType(node, m)).ifPresent(why -> report(node, why));
                follow(m, chain);
            }
            return super.visitMethodInvocation(node, v);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void v) {
            if (trees.getElement(getCurrentPath()) instanceof ExecutableElement ctor) {
                blocking.constructor(ctor).ifPresent(why -> report(node, why));
                follow(ctor, chain);
            }
            // An anonymous class body runs when its methods are called, not here.
            scan(node.getEnclosingExpression(), v);
            scan(node.getArguments(), v);
            return null;
        }

        @Override
        public Void visitClass(ClassTree node, Void v) {
            return null; // a local class's methods run when called, not here
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree node, Void v) {
            TreePath path = getCurrentPath();
            if (isOffLoop(path) || isRoot(path)) return null; // another thread's, or its own root
            return super.visitLambdaExpression(node, v);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void v) {
            TreePath path = getCurrentPath();
            if (isOffLoop(path) || isRoot(path)) return null;
            if (trees.getElement(path) instanceof ExecutableElement m) {
                blocking.method(m, null).ifPresent(why -> report(node, why));
                follow(m, chain);
            }
            return super.visitMemberReference(node, v);
        }

        private TypeMirror receiverType(MethodInvocationTree node, ExecutableElement m) {
            if (node.getMethodSelect() instanceof MemberSelectTree ms) {
                TypeMirror t = trees.getTypeMirror(new TreePath(new TreePath(getCurrentPath(), ms), ms.getExpression()));
                if (t != null && (t.getKind() == TypeKind.DECLARED || t.getKind() == TypeKind.TYPEVAR)) return t;
            }
            return m.getEnclosingElement().asType();
        }

        private void report(Tree tree, String why) {
            if (!reported.add(tree)) return;
            StringBuilder msg = new StringBuilder("[").append(EventLoopCheck.NAME).append("] ")
                    .append(why).append(", on a Vert.x event loop.\n  Runs on the loop: ").append(chain.getFirst());
            if (chain.size() > 1) {
                msg.append("\n  via ").append(String.join(" -> ", chain.subList(1, chain.size())));
            }
            msg.append("\n  Do the blocking work on a virtual thread and hop back with runOnContext, or use the "
                    + "non-blocking Vert.x API (docs/spec/eventloop-check.md).");
            trees.printMessage(Diagnostic.Kind.ERROR, msg, tree, getCurrentPath().getCompilationUnit());
        }
    }

    /// Queues `m`'s body for scanning, once, when its source is in this compilation.
    private void follow(ExecutableElement m, List<String> chain) {
        TreePath path = trees.getPath(m);
        if (path == null || !(path.getLeaf() instanceof MethodTree mt)) return;
        BlockTree body = mt.getBody();
        if (body == null || !followed.add(m)) return;
        List<String> next = new ArrayList<>(chain);
        if (!next.getFirst().startsWith(describe(m))) next.add(describe(m));
        work.add(new Work(new TreePath(path, body), List.copyOf(next)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TypeMirror type(String name) {
        TypeElement e = elements.getTypeElement(name);
        return e == null ? null : types.erasure(e.asType());
    }

    private boolean subtype(TypeMirror t, TypeMirror of) {
        return of != null && t.getKind() == TypeKind.DECLARED && types.isSubtype(t, of);
    }

    private static boolean isVertx(TypeMirror t) {
        return t instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te
                && te.getQualifiedName().toString().startsWith(VERTX);
    }

    private static boolean isVertx(ExecutableElement m) {
        return m.getEnclosingElement() instanceof TypeElement te && te.getQualifiedName().toString().startsWith(VERTX);
    }

    private static boolean annotated(Element e, String annotation) {
        if (e == null) return false;
        for (AnnotationMirror a : e.getAnnotationMirrors()) {
            if (((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(ExecutableElement m) {
        String owner = m.getEnclosingElement().getSimpleName().toString();
        String name = m.getSimpleName().contentEquals("<init>") ? "new " + owner : owner + "." + m.getSimpleName();
        return name + "(…)";
    }

    private String where(TreePath path) {
        CompilationUnitTree cu = path.getCompilationUnit();
        long pos = trees.getSourcePositions().getStartPosition(cu, path.getLeaf());
        return fileName(cu) + ":" + cu.getLineMap().getLineNumber(pos);
    }

    private static String fileName(CompilationUnitTree cu) {
        String name = cu.getSourceFile().getName();
        return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    }
}
