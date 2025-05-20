package cider.nrepl.middleware;

import clojure.lang.*;

/**
 * Contains instrumentation helpers for cider.nrepl.middleware.debug. The main
 * purpose of having these helpers in Java is reducing the instrumentation
 * footprint (measured in bytecode size). Invoking Java methods usually takes
 * fewer bytecode instructions than a corresponding Clojure function. Java also
 * allows us to have primitive overrides which further reduces overhead when
 * instrumenting code that contains primitives, but also preserves type hints
 * (so instrumented code behaves closer to original code).
 *
 * The reason we care about bytecode size is 65KB method limit that JVM imposes.
 */
public class DebugSupport {

    private static volatile IFn breakFn = null;

    public static Object doBreak(Object coor, Object val, Object locals, Object STATE__) {
        if (breakFn == null)
            breakFn = (IFn)RT.var("cider.nrepl.middleware.debug", "break");
        return breakFn.invoke(coor, val, locals, STATE__);
    }

    public static long doBreak(Object coor, long val, Object locals, Object STATE__) {
        return (long)doBreak(coor, Numbers.num(val), locals, STATE__);
    }

    public static double doBreak(Object coor, double val, Object locals, Object STATE__) {
        return (double)doBreak(coor, Numbers.num(val), locals, STATE__);
    }
}
