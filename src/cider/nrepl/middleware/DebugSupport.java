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

    // The purpose of the following assoc methods is to build a locals map.
    // Chaining such assoc calls is more bytecode-compact than a single
    // RT.mapUniqueKeys(...) because the latter constructs an array (load each
    // key and value onto the stack, save them into the array) and boxes
    // primitives (invocations of Numbers.num). Additionally, in this custom
    // method we turn string keys into symbols, which means we don't have to
    // generate symbols at the callsite (in the instrumented method). This saves
    // bytecode because LDC of a constant string is more compact than
    // ALOAD+GETFIELD of an interned symbol.

    public static IPersistentMap assoc(Object map, Object key, Object value) {
        return ((IPersistentMap)map).assoc(Symbol.intern(null, (String)key), value);
    }

    public static IPersistentMap assoc(Object map, Object key, long value) {
        return assoc(map, key, Numbers.num(value));
    }

    public static IPersistentMap assoc(Object map, Object key, double value) {
        return assoc(map, key, Numbers.num(value));
    }
}
