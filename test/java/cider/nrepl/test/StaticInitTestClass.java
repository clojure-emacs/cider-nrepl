package cider.nrepl.test;

public class StaticInitTestClass {
    static {
        System.setProperty("cider.nrepl.test.StaticInitTestClass", "initialized");
    }

    public static String now() {
        return "now";
    }
}
