package dev.chatfilter;

import java.util.regex.Pattern;

/**
 * Eine Filterregel. Der Text darf Platzhalter für variable Teile enthalten:
 * {*}  = ein Wort ohne Leerzeichen (z.B. Spielername, Zahl)
 * {**} = beliebiger Text, auch mit Leerzeichen
 */
public class FilterRule {
	/** Das Muster (Groß-/Kleinschreibung egal). */
	public String text = "";

	/**
	 * false = ganze Nachricht ausblenden, in der das Muster vorkommt.
	 * true  = nur das passende Stück aus der Nachricht herausschneiden.
	 */
	public boolean removeOnlyText = false;

	/** Zwischengespeichertes Regex-Muster (wird nicht in der Config gespeichert). */
	transient Pattern compiled;

	public FilterRule() {
	}

	public FilterRule(String text, boolean removeOnlyText) {
		this.text = text;
		this.removeOnlyText = removeOnlyText;
	}
}
