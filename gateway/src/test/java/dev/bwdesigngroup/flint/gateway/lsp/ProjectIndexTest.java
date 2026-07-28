package dev.bwdesigngroup.flint.gateway.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bwdesigngroup.flint.common.protocol.methods.lsp.CompletionItem;
import dev.bwdesigngroup.flint.common.protocol.methods.lsp.Location;
import dev.bwdesigngroup.flint.common.protocol.methods.lsp.Position;
import dev.bwdesigngroup.flint.common.protocol.methods.lsp.WorkspaceSymbol;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Phase 5: cross-file project intelligence (index, completion, definition, workspace symbols). */
class ProjectIndexTest {

    /** In-memory store with two script modules. */
    private static FakeScriptStore fakeStore() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("util/math", "def add(a, b):\n    return a + b\n\nPI = 3.14159\n");
        sources.put("app/main", "import util.math\n\ndef run():\n    return util.math.add(1, 2)\n");
        return new FakeScriptStore("proj", sources);
    }

    private FlintLanguageServer server() {
        return new FlintLanguageServer(null, new ProjectIndex(fakeStore()));
    }

    @Test
    void indexesModulesByDottedPath() {
        ProjectIndex idx = new ProjectIndex(fakeStore());
        assertNotNull(idx.module("proj", "util.math"));
        assertNotNull(idx.module("proj", "app.main"));
        assertTrue(idx.rootPackages("proj").contains("util"));
    }

    @Test
    void childPackagesListsImmediateChildrenOfAPackage() {
        ProjectIndex idx =
                new ProjectIndex(
                        FakeScriptStore.ofPaths(
                                "proj",
                                "application/test",
                                "application/refrigeration_service",
                                "common/util"));
        assertEquals(
                Arrays.asList("test", "refrigeration_service"),
                idx.childPackages("proj", "application"));
    }

    @Test
    void childPackagesReturnsOnlyTheNextSegment() {
        ProjectIndex idx = new ProjectIndex(FakeScriptStore.ofPaths("proj", "a/b/c"));
        assertEquals(Arrays.asList("b"), idx.childPackages("proj", "a"));
        assertEquals(Arrays.asList("c"), idx.childPackages("proj", "a.b"));
    }

    @Test
    void childPackagesMatchesOnSegmentBoundaries() {
        ProjectIndex idx =
                new ProjectIndex(FakeScriptStore.ofPaths("proj", "application/test", "app/main"));
        assertEquals(Arrays.asList("main"), idx.childPackages("proj", "app"));
        assertTrue(idx.childPackages("proj", "appl").isEmpty());
    }

    @Test
    void childPackagesDeduplicatesSharedChildPackages() {
        ProjectIndex idx =
                new ProjectIndex(
                        FakeScriptStore.ofPaths(
                                "proj", "application/sub/one", "application/sub/two"));
        assertEquals(Arrays.asList("sub"), idx.childPackages("proj", "application"));
    }

    @Test
    void rootPackagesIsTheEmptyPrefixCase() {
        ProjectIndex idx =
                new ProjectIndex(
                        FakeScriptStore.ofPaths(
                                "proj", "application/test", "application/other", "common/util"));
        assertEquals(Arrays.asList("application", "common"), idx.rootPackages("proj"));
        assertEquals(idx.rootPackages("proj"), idx.childPackages("proj", ""));
    }

    @Test
    void crossFileCompletionOfModuleMembers() {
        // In app/main, completing "util.math." should list add + PI from the other file.
        String text = "import util.math\nx = util.math.\n";
        List<CompletionItem> items =
                server().completion("s", "app/main", text, new Position(1, 14), "proj");
        List<String> labels =
                items.stream().map(CompletionItem::getLabel).collect(Collectors.toList());
        assertTrue(labels.contains("add"), labels.toString());
        assertTrue(labels.contains("PI"), labels.toString());
    }

    @Test
    void crossFileDefinitionIntoOtherModule() {
        String text = "import util.math\nx = util.math.add(1, 2)\n"; // 'add' at line1 col14..
        Location def =
                server().definition(
                                "s", "app/main", text, new Position(1, 15), "proj", "file:///ws");
        assertNotNull(def, "should resolve cross-file to util/math");
        assertTrue(def.uri.contains("util/math"), def.uri);
        assertEquals(0, def.range.start.line, "add is on line 0 of util/math");
    }

    @Test
    void workspaceSymbolSearch() {
        List<WorkspaceSymbol> syms = server().workspaceSymbols("proj", "add", "file:///ws");
        assertTrue(
                syms.stream()
                        .anyMatch(
                                s -> "add".equals(s.name) && s.location.uri.contains("util/math")),
                syms.stream().map(s -> s.name).collect(Collectors.toList()).toString());
    }

    /**
     * Both routes build URIs under the project folder the client declared, not the workspace root —
     * the repo layout nests the project under a {@code project-paths} entry.
     */
    @Test
    void crossFileDefinitionUsesDeclaredProjectRoot() {
        String projectRoot = LspUris.projectRootUri("projects/proj", "file:///ws");
        String text = "import util.math\nx = util.math.add(1, 2)\n";
        Location def =
                server().definition(
                                "s", "app/main", text, new Position(1, 15), "proj", projectRoot);
        assertNotNull(def);
        assertEquals("file:///ws/projects/proj/ignition/script-python/util/math/code.py", def.uri);
    }

    @Test
    void workspaceSymbolsUseDeclaredProjectRoot() {
        String projectRoot = LspUris.projectRootUri("projects/proj", "file:///ws");
        List<WorkspaceSymbol> syms = server().workspaceSymbols("proj", "add", projectRoot);
        assertTrue(
                syms.stream()
                        .anyMatch(
                                s ->
                                        "add".equals(s.name)
                                                && s.location.uri.startsWith(
                                                        "file:///ws/projects/proj/ignition/")),
                syms.stream().map(s -> s.location.uri).collect(Collectors.toList()).toString());
    }
}
