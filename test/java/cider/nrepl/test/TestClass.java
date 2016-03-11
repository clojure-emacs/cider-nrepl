package cider.nrepl.test;

public class TestClass {
    public TestClass() {
    }

    public int getInt() {
        return 3;
    }

    public boolean fnWithSameName() {
        return true;
    }

    private static void doSomething(int a, int b, String c) {
        String x = c + a + b;
    }
}
