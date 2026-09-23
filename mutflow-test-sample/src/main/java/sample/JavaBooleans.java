package sample;

/** A Java source of boolean values, which Kotlin sees as platform-typed {@code Boolean!}. */
public final class JavaBooleans {
    private JavaBooleans() {}

    /** Declared as a boolean, but returns null, which Kotlin cannot rule out for Java code. */
    public static Boolean missing() {
        return null;
    }
}
