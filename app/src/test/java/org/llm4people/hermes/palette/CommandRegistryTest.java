package org.llm4people.hermes.palette;

import android.view.KeyEvent;
import org.junit.Test;
import org.llm4people.hermes.templates.WorkflowTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public final class CommandRegistryTest {
    @Test public void accentInsensitiveNormalizationAndMultiTokenRanking() {
        assertEquals("memoire de hermes", CommandRegistry.normalize("Mémoire de Hermes !"));
        assertEquals("securite verrouillage", CommandRegistry.normalize("Sécurité & verrouillage"));
        assertEquals("", CommandRegistry.normalize("   \t\n  "));

        CommandRegistry registry = new CommandRegistry(java.util.Collections.emptyList());
        registry.add(new CommandItem("act:memory", CommandItem.Category.ACTION,
                "Mémoire de Hermes", "Voir et modifier ce que Hermes retient", "memory brain", null));
        registry.add(new CommandItem("act:notes", CommandItem.Category.ACTION,
                "Mes notes", "Consulter ou créer des notes personnelles", "smart notes", null));
        registry.add(new CommandItem("act:security", CommandItem.Category.ACTION,
                "Sécurité et verrouillage", "Biométrie et verrouillage immédiat", "lock vault", null));
        registry.add(new CommandItem("thr:1", CommandItem.Category.THREAD,
                "Revue mémoire cache", "Discussion technique", "thread", null));

        // Accent-insensitive prefix beats substring match.
        List<CommandItem> mem = registry.search("memoire");
        assertEquals(2, mem.size());
        assertEquals("act:memory", mem.get(0).id);
        assertEquals("thr:1", mem.get(1).id);

        // Multi-token match requires all tokens.
        List<CommandItem> notes = registry.search("notes perso");
        assertEquals(1, notes.size());
        assertEquals("act:notes", notes.get(0).id);
        assertTrue(registry.search("notes inexistant").isEmpty());

        // Ordered fuzzy subsequence match (>= 3 chars).
        List<CommandItem> fuzzy = registry.search("secver");
        assertEquals(1, fuzzy.size());
        assertEquals("act:security", fuzzy.get(0).id);
    }

    @Test public void emptyQueryOrdersByCategoryAndCapsResults() {
        CommandRegistry registry = new CommandRegistry(java.util.Collections.emptyList());
        for (int i = 0; i < 60; i++) {
            CommandItem.Category cat = i % 2 == 0 ? CommandItem.Category.THREAD : CommandItem.Category.ACTION;
            registry.add(new CommandItem("id:" + i, cat, "Item " + i, "Subtitle " + i, "", null));
        }
        List<CommandItem> top = registry.search("");
        assertEquals(CommandRegistry.MAX_RESULTS, top.size());
        assertEquals(CommandItem.Category.ACTION, top.get(0).category);
        assertEquals(CommandItem.Category.THREAD, top.get(top.size() - 1).category);

        // Oversized query never crashes and returns bounded results.
        String huge = "a".repeat(1000);
        assertTrue(registry.search(huge).isEmpty());
    }

    @Test public void sanitizesControlCharsRejectsBlankAndDeduplicates() {
        CommandItem clean = new CommandItem("  act:one\n ", CommandItem.Category.ACTION,
                "Titre\t\r\npropre", "Sous\u0000titre", "mot\u0007cle", null);
        assertEquals("act:one", clean.id);
        assertEquals("Titre propre", clean.title);
        assertEquals("Soustitre", clean.subtitle);
        assertEquals("motcle", clean.keywords);

        // R18.4.2: Strips bidi/format/Plane-14/CGJ/unpaired surrogates and collapses non-ASCII spaces.
        CommandItem sanitized = new CommandItem("act\u202E\u034F:two", CommandItem.Category.ACTION,
                "Bon\u202E\u200B\uDB40\uDC20jour\u00A0\u202F\u3000monde\uD83D",
                "a".repeat(CommandItem.MAX_SUBTITLE_CHARS - 1) + "\uD83D\uDE00",
                "clé\u034F\u00A0une", null);
        assertEquals("act:two", sanitized.id);
        assertEquals("Bonjour monde", sanitized.title);
        assertEquals("a".repeat(CommandItem.MAX_SUBTITLE_CHARS - 1), sanitized.subtitle);
        assertEquals("clé une", sanitized.keywords);

        for (String bad : new String[]{null, "", "   \n\t ", "\u202E\u034F", "\u00A0", "\uFE0F", "\u115F\u3164"}) {
            try {
                new CommandItem(bad, CommandItem.Category.ACTION, "Ok", "", "", null);
                fail("Expected blank id rejection");
            } catch (IllegalArgumentException expected) {}
            try {
                new CommandItem("ok", CommandItem.Category.ACTION, bad, "", "", null);
                fail("Expected blank title rejection");
            } catch (IllegalArgumentException expected) {}
        }

        CommandRegistry registry = new CommandRegistry(java.util.Collections.emptyList());
        registry.add(new CommandItem("dup", CommandItem.Category.ACTION, "First", "", "", null));
        registry.add(new CommandItem("dup", CommandItem.Category.ACTION, "Updated", "", "", null));
        assertEquals(1, registry.search("").size());
        assertEquals("Updated", registry.search("updated").get(0).title);
    }

    @Test public void templatesAndRecentThreadsWireSafelyWithoutAutoSending() throws Exception {
        AtomicReference<String> staged = new AtomicReference<>();
        WorkflowTemplate tpl = new WorkflowTemplate("tpl_1", "Bilan", "Hebdo", "Prépare mon bilan.", false);
        List<CommandItem> tplItems = CommandRegistry.fromTemplates(List.of(tpl), staged::set);
        assertEquals(1, tplItems.size());
        assertEquals(CommandItem.Category.TEMPLATE, tplItems.get(0).category);
        tplItems.get(0).action.run();
        assertEquals("Prépare mon bilan.", staged.get());

        AtomicReference<String> opened = new AtomicReference<>();
        List<CommandRegistry.RecentThread> raw = new ArrayList<>();
        raw.add(new CommandRegistry.RecentThread("s1", "Discussion A", "Aperçu"));
        raw.add(new CommandRegistry.RecentThread("s1", "Duplicate A", "Ignored"));
        raw.add(new CommandRegistry.RecentThread("  ", "Blank session", ""));
        raw.add(new CommandRegistry.RecentThread("s2", "   ", "Blank title"));
        raw.add(new CommandRegistry.RecentThread("s3", "Discussion B", null));
        List<CommandItem> threadItems = CommandRegistry.fromRecentThreads(raw, opened::set);
        assertEquals(2, threadItems.size());
        threadItems.get(1).action.run();
        assertEquals("s3", opened.get());

        assertTrue(CommandRegistry.isPaletteShortcut(KeyEvent.KEYCODE_K, true, false));
        assertTrue(CommandRegistry.isPaletteShortcut(KeyEvent.KEYCODE_K, false, true));
        assertFalse(CommandRegistry.isPaletteShortcut(KeyEvent.KEYCODE_K, false, false));
        assertFalse(CommandRegistry.isPaletteShortcut(KeyEvent.KEYCODE_A, true, false));
    }
}
