package machine;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;

/** One presentation scale shared by an application's source and diagram editors. */
public final class EditorZoom {
    public static final double MIN_FACTOR = 0.5;
    public static final double MAX_FACTOR = 3.0;
    private final ReadOnlyDoubleWrapper factor = new ReadOnlyDoubleWrapper(this, "factor", 1.0);

    public ReadOnlyDoubleProperty factorProperty() { return factor.getReadOnlyProperty(); }
    public double getFactor() { return factor.get(); }

    public void install(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!shortcut(event)) return;
            int direction = switch (event.getCode()) {
                case PLUS, EQUALS, ADD -> 1;
                case MINUS, SUBTRACT -> -1;
                default -> 0;
            };
            if (direction == 0) return;
            double next = Math.round((factor.get() + direction * 0.1) * 10) / 10.0;
            factor.set(Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, next)));
            event.consume();
        });
        scene.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (shortcut(event) && (event.getCharacter().equals("+")
                    || event.getCharacter().equals("-") || event.getCharacter().equals("="))) event.consume();
        });
    }

    private static boolean shortcut(KeyEvent event) {
        // AltGr commonly includes Ctrl; it must continue to enter ordinary source characters.
        return !event.isConsumed() && !event.isAltDown() && (event.isControlDown() || event.isShortcutDown());
    }
}
