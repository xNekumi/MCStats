package de.mcstats.text;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Wandelt Chat-Komponenten mit den geladenen {@link Translations} in reinen Text um. */
public final class TextRenderer {
	private final Translations translations;

	public TextRenderer(Translations translations) {
		this.translations = translations;
	}

	public String render(Component component) {
		StringBuilder out = new StringBuilder();
		append(component, out, 0);
		return out.toString();
	}

	private void append(Component component, StringBuilder out, int depth) {
		if (depth > 16) {
			return;
		}
		if (component.getContents() instanceof TranslatableContents translatable) {
			format(translations.get(translatable.getKey(), translatable.getFallback()), translatable.getArgs(), out, depth);
		} else {
			component.getContents().visit(text -> {
				out.append(text);
				return Optional.empty();
			});
		}
		for (Component sibling : component.getSiblings()) {
			append(sibling, out, depth + 1);
		}
	}

	/** Minecraft-Platzhalter: %s, %1$s und %%. */
	private void format(String pattern, Object[] args, StringBuilder out, int depth) {
		int next = 0;
		int i = 0;
		while (i < pattern.length()) {
			char c = pattern.charAt(i);
			if (c != '%' || i + 1 >= pattern.length()) {
				out.append(c);
				i++;
				continue;
			}
			char n = pattern.charAt(i + 1);
			if (n == '%') {
				out.append('%');
				i += 2;
			} else if (n == 's') {
				appendArg(args, next++, out, depth);
				i += 2;
			} else if (Character.isDigit(n)) {
				int end = i + 1;
				while (end < pattern.length() && Character.isDigit(pattern.charAt(end))) end++;
				if (end + 1 < pattern.length() && pattern.charAt(end) == '$' && pattern.charAt(end + 1) == 's') {
					appendArg(args, Integer.parseInt(pattern.substring(i + 1, end)) - 1, out, depth);
					i = end + 2;
				} else {
					out.append(c);
					i++;
				}
			} else {
				out.append(c);
				i++;
			}
		}
	}

	private void appendArg(Object[] args, int index, StringBuilder out, int depth) {
		if (index < 0 || index >= args.length) {
			return;
		}
		Object arg = args[index];
		if (arg instanceof Component component) {
			append(component, out, depth + 1);
		} else {
			out.append(arg);
		}
	}
}
