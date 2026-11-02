package machine;

import java.util.Locale;

/** Language of the teaching text; independent of the compiler's language version. */
public enum GameTaskLanguage {
    ENGLISH("en", "English"),
    GERMAN("de", "Deutsch");

    private final String code;
    private final String title;

    GameTaskLanguage(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String code() { return code; }

    /** German system locales use German tasks; all other locales use English. */
    public static GameTaskLanguage fromLocale(Locale locale) {
        return locale != null && "de".equals(locale.getLanguage()) ? GERMAN : ENGLISH;
    }

    /** Unknown or missing saved preferences fall back to the system locale. */
    public static GameTaskLanguage fromCode(String code) {
        if (code == null) return null;
        for (GameTaskLanguage language : values()) {
            if (language.code.equalsIgnoreCase(code.trim())) return language;
        }
        return null;
    }

    @Override public String toString() { return title; }
}
