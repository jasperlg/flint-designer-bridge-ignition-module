package dev.bwdesigngroup.flint.gateway.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** URI construction for cross-file definitions and workspace symbols. */
class LspUrisTest {

    /**
     * The regression these tests exist for: a repo whose scripts live under a {@code
     * projects/<name>/} prefix, not at the workspace root. Builds the layout on disk and asserts
     * the URI we hand the editor points at a file that is actually there.
     */
    @Nested
    class ResolvesToRealFileOnDisk {

        private Path writeScript(Path projectRoot, String resourcePath) throws IOException {
            Path dir = projectRoot.resolve("ignition/script-python").resolve(resourcePath);
            Files.createDirectories(dir);
            Path code = dir.resolve("code.py");
            Files.write(code, "def foo():\n    pass\n".getBytes("UTF-8"));
            return code;
        }

        @Test
        void nestedProjectLayout(@TempDir Path workspace) throws IOException {
            // <workspace>/projects/example-project/ignition/script-python/application/test/code.py
            Path projectRoot = workspace.resolve("projects/example-project");
            Path expected = writeScript(projectRoot, "application/test");

            String rootUri = workspace.toUri().toString();
            String base = LspUris.projectRootUri(projectRoot.toUri().toString(), rootUri);
            String uri = LspUris.scriptUri(base, "example-project", "application/test");

            Path resolved = Paths.get(URI.create(uri));
            assertTrue(Files.exists(resolved), "URI does not resolve to an existing file: " + uri);
            assertEquals(expected.toRealPath(), resolved.toRealPath());

            // The bug this guards: anchoring at the workspace root points at nothing.
            String buggy = LspUris.scriptUri(rootUri, "example-project", "application/test");
            assertFalse(Files.exists(Paths.get(URI.create(buggy))), buggy);
        }

        @Test
        void nestedProjectLayoutFromWorkspaceRelativeProjectRoot(@TempDir Path workspace)
                throws IOException {
            Path projectRoot = workspace.resolve("projects/example-project");
            Path expected = writeScript(projectRoot, "application/test");

            String rootUri = workspace.toUri().toString();
            String base = LspUris.projectRootUri("projects/example-project", rootUri);
            String uri = LspUris.scriptUri(base, "example-project", "application/test");

            Path resolved = Paths.get(URI.create(uri));
            assertTrue(Files.exists(resolved), "URI does not resolve to an existing file: " + uri);
            assertEquals(expected.toRealPath(), resolved.toRealPath());
        }

        /**
         * Workspace root already is the project folder — the case that worked before {@code
         * projectRoot} existed, and must keep working for clients that never send it.
         */
        @Test
        void flatLayoutWithNoDeclaredProjectRoot(@TempDir Path workspace) throws IOException {
            Path expected = writeScript(workspace, "application/test");

            String base = LspUris.projectRootUri(null, workspace.toUri().toString());
            String uri = LspUris.scriptUri(base, "example-project", "application/test");

            Path resolved = Paths.get(URI.create(uri));
            assertTrue(Files.exists(resolved), "URI does not resolve to an existing file: " + uri);
            assertEquals(expected.toRealPath(), resolved.toRealPath());
        }

        /** A workspace root with a space exercises percent-encoding end to end. */
        @Test
        void pathNeedingPercentEncoding(@TempDir Path tmp) throws IOException {
            Path workspace = tmp.resolve("my repo");
            Path projectRoot = workspace.resolve("projects/example-project");
            Path expected = writeScript(projectRoot, "application/test");

            String base =
                    LspUris.projectRootUri(
                            projectRoot.toAbsolutePath().toString(), workspace.toUri().toString());
            String uri = LspUris.scriptUri(base, "example-project", "application/test");

            assertTrue(uri.contains("my%20repo"), "space not encoded: " + uri);
            Path resolved = Paths.get(URI.create(uri));
            assertTrue(Files.exists(resolved), "URI does not resolve to an existing file: " + uri);
            assertEquals(expected.toRealPath(), resolved.toRealPath());
        }
    }

    @Nested
    class ProjectRootResolution {

        @Test
        void declaredUriWins() {
            assertEquals(
                    "file:///repo/projects/example",
                    LspUris.projectRootUri("file:///repo/projects/example", "file:///repo"));
        }

        @Test
        void fallsBackToWorkspaceRootWhenUndeclared() {
            assertEquals("file:///repo", LspUris.projectRootUri(null, "file:///repo"));
            assertEquals("file:///repo", LspUris.projectRootUri("   ", "file:///repo"));
        }

        @Test
        void relativePathIsJoinedToWorkspaceRoot() {
            assertEquals(
                    "file:///repo/projects/example",
                    LspUris.projectRootUri("projects/example", "file:///repo"));
            assertEquals(
                    "file:///repo/projects/example",
                    LspUris.projectRootUri("./projects/example/", "file:///repo/"));
        }

        @Test
        void absolutePosixPathBecomesFileUri() {
            assertEquals(
                    "file:///repo/projects/example",
                    LspUris.projectRootUri("/repo/projects/example", null));
        }

        @Test
        void windowsPathBecomesFileUriAndIsNotMistakenForAScheme() {
            assertEquals(
                    "file:///C:/repo/projects/example",
                    LspUris.projectRootUri("C:\\repo\\projects\\example", null));
        }

        @Test
        void nullWhenNothingUsable() {
            assertNull(LspUris.projectRootUri(null, null));
            // A relative project root is meaningless without a workspace to resolve it against.
            assertNull(LspUris.projectRootUri("projects/example", null));
        }
    }

    @Nested
    class ScriptUri {

        @Test
        void buildsUnderProjectRoot() {
            assertEquals(
                    "file:///repo/projects/example/ignition/script-python/application/test/code.py",
                    LspUris.scriptUri(
                            "file:///repo/projects/example", "example", "application/test"));
        }

        @Test
        void tolerantOfTrailingSlash() {
            assertEquals(
                    "file:///repo/ignition/script-python/application/test/code.py",
                    LspUris.scriptUri("file:///repo/", "example", "application/test"));
        }

        @Test
        void fallsBackToFlintSchemeWithoutARoot() {
            assertEquals(
                    "flint://example/ignition/script-python/application/test/code.py",
                    LspUris.scriptUri(null, "example", "application/test"));
            assertEquals(
                    "flint://project/ignition/script-python/application/test/code.py",
                    LspUris.scriptUri("", null, "application/test"));
        }
    }
}
