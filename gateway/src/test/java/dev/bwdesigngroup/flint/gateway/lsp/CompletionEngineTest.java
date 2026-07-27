package dev.bwdesigngroup.flint.gateway.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bwdesigngroup.flint.common.protocol.methods.lsp.CompletionItem;
import dev.bwdesigngroup.flint.common.protocol.methods.lsp.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Phase 4: position-aware completion (scope symbols + system.* hints + keywords). */
class CompletionEngineTest {

    private final JythonParseService parse = new JythonParseService();

    /**
     * Minimal fake hints source mimicking system.* -> {tag, date, ...}, system.tag ->
     * {readBlocking}.
     */
    private static class FakeHints implements HintsSource {
        @Override
        public Set<String> rootNames() {
            return new HashSet<>(Arrays.asList("system"));
        }

        @Override
        public List<CompletionItem> members(String dottedPath, String partial) {
            List<CompletionItem> out = new ArrayList<>();
            if ("system".equals(dottedPath)) {
                for (String m : new String[] {"tag", "date", "perspective"}) {
                    if (m.startsWith(partial.toLowerCase())) {
                        out.add(new CompletionItem(m, 9));
                    }
                }
            } else if ("system.tag".equals(dottedPath)) {
                for (String m : new String[] {"readBlocking", "writeBlocking"}) {
                    if (m.toLowerCase().startsWith(partial.toLowerCase())) {
                        out.add(new CompletionItem(m, 3));
                    }
                }
            }
            return out;
        }
    }

    private List<String> completeLabels(String src, int line, int ch) {
        CompletionEngine engine = new CompletionEngine(new FakeHints());
        return engine.complete(parse.parse("m.py", src).ast, src, new Position(line, ch)).stream()
                .map(CompletionItem::getLabel)
                .collect(Collectors.toList());
    }

    /** Project fixture mirroring a real script library: packages plus one leaf module with code. */
    private static ProjectIndex projectIndex() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("application/test", "def foo():\n    pass\n");
        sources.put("application/refrigeration_service", "");
        sources.put("application/sub/one", "");
        sources.put("application/sub/two", "");
        sources.put("app/main", "");
        sources.put("a/b/c", "");
        return new ProjectIndex(new FakeScriptStore("proj", sources));
    }

    private List<String> completeLabelsInProject(String src, int line, int ch) {
        CompletionEngine engine = new CompletionEngine(new FakeHints());
        return engine
                .complete(
                        parse.parse("m.py", src).ast,
                        src,
                        new Position(line, ch),
                        projectIndex(),
                        "proj")
                .stream()
                .map(CompletionItem::getLabel)
                .collect(Collectors.toList());
    }

    @Test
    void memberCompletionOfSystemModule() {
        // "system." then complete -> tag/date/perspective
        String src = "x = system.\n";
        List<String> labels = completeLabels(src, 0, 11); // after "system."
        assertTrue(labels.contains("tag"), labels.toString());
        assertTrue(labels.contains("date"), labels.toString());
    }

    @Test
    void memberCompletionNested() {
        String src = "x = system.tag.re\n";
        List<String> labels = completeLabels(src, 0, 17); // after "system.tag.re"
        assertTrue(labels.contains("readBlocking"), labels.toString());
        assertFalse(labels.contains("writeBlocking"), labels.toString());
    }

    @Test
    void bareCompletionIncludesScopeSymbolsAndRoots() {
        String src =
                "def readValues(paths):\n"
                        + "    return paths\n"
                        + "\n"
                        + "def readNames():\n"
                        + "    r\n"; // completing 'r' at module-visible scope
        List<String> labels = completeLabels(src, 4, 5);
        assertTrue(labels.contains("readValues"), labels.toString());
        assertTrue(labels.contains("readNames"), labels.toString());
        assertTrue(labels.contains("return"), "keywords expected: " + labels);
    }

    @Test
    void bareCompletionIncludesSystemRoot() {
        String src = "sy\n";
        List<String> labels = completeLabels(src, 0, 2);
        assertTrue(labels.contains("system"), labels.toString());
    }

    @Test
    void localParameterVisibleInCompletion() {
        String src = "def scale(value):\n    v\n";
        List<String> labels = completeLabels(src, 1, 5);
        assertTrue(labels.contains("value"), labels.toString());
    }

    @Test
    void intermediatePackageCompletesToItsChildren() {
        String src = "x = application.\n";
        List<String> labels = completeLabelsInProject(src, 0, 16);
        assertTrue(labels.contains("test"), labels.toString());
        assertTrue(labels.contains("refrigeration_service"), labels.toString());
    }

    @Test
    void intermediatePackageOffersOnlyImmediateChildren() {
        String src = "x = a.\n";
        List<String> labels = completeLabelsInProject(src, 0, 6);
        assertTrue(labels.contains("b"), labels.toString());
        assertFalse(labels.contains("b.c"), labels.toString());
        assertFalse(labels.contains("c"), labels.toString());
    }

    @Test
    void packageCompletionMatchesOnSegmentBoundaries() {
        // "app." is its own package (app/main); it must not spill children of "application".
        String src = "x = app.\n";
        List<String> labels = completeLabelsInProject(src, 0, 8);
        assertTrue(labels.contains("main"), labels.toString());
        assertFalse(labels.contains("test"), labels.toString());
    }

    @Test
    void childPackageWithSeveralModulesAppearsOnce() {
        String src = "x = application.\n";
        List<String> labels = completeLabelsInProject(src, 0, 16);
        assertEquals(
                1,
                labels.stream().filter("sub"::equals).count(),
                "'sub' should appear once: " + labels);
    }

    @Test
    void packageCompletionRespectsPartial() {
        String src = "x = application.te\n";
        List<String> labels = completeLabelsInProject(src, 0, 18);
        assertTrue(labels.contains("test"), labels.toString());
        assertFalse(labels.contains("refrigeration_service"), labels.toString());
    }

    @Test
    void leafModuleMemberCompletionStillWorks() {
        String src = "x = application.test.\n";
        List<String> labels = completeLabelsInProject(src, 0, 21);
        assertTrue(labels.contains("foo"), labels.toString());
    }

    @Test
    void systemHintsUnaffectedByProjectIndex() {
        String src = "x = system.\n";
        List<String> labels = completeLabelsInProject(src, 0, 11);
        assertTrue(labels.contains("tag"), labels.toString());
        assertTrue(labels.contains("date"), labels.toString());
        assertTrue(labels.contains("perspective"), labels.toString());
    }

    @Test
    void bareCompletionStillOffersProjectRoots() {
        String src = "a\n";
        List<String> labels = completeLabelsInProject(src, 0, 1);
        assertTrue(labels.contains("application"), labels.toString());
        assertFalse(labels.contains("test"), labels.toString());
    }
}
