package dev.bwdesigngroup.flint.gateway.lsp;

import java.nio.charset.StandardCharsets;

/**
 * Maps a project script resource path to a document URI.
 *
 * <p>Ignition stores a project's scripts at {@code <projectRoot>/ignition/script-python/<resource
 * path>/code.py}, where {@code <projectRoot>} is the project folder on disk. That folder equals the
 * editor's workspace root only when the editor is opened on the project folder itself; when it is
 * opened on an ancestor — a repo holding the project under a {@code project-paths} entry, e.g.
 * {@code <workspaceRoot>/projects/<projectName>/} — the workspace root is several segments short.
 * The nesting depth is what matters here, not how many projects the workspace contains.
 *
 * <p>The gateway cannot see the client's filesystem or its {@code project-paths} config, so the
 * client declares its project folder at {@code initialize} via {@code
 * initializationOptions.projectRoot} and every URI is built relative to that. One project root per
 * session, matching the single {@code initializationOptions.project} it accompanies.
 *
 * <p>With no declared project root we fall back to the workspace root, which stays correct when the
 * two coincide; with neither, a {@code flint://} URI — not a file path, but a stable identifier
 * agents can still use.
 */
public final class LspUris {

    /** RFC 3986 path characters that survive percent-encoding. */
    private static final String PATH_SAFE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/";

    private LspUris() {}

    /**
     * Resolves the base URI of the project folder from what the client sent at {@code initialize}.
     *
     * <p>{@code projectRoot} may be a URI ({@code file:///repo/projects/example}), an absolute
     * filesystem path ({@code /repo/projects/example} or {@code C:\repo\projects\example}), or a
     * path relative to the workspace ({@code projects/example}). When it is absent or unusable,
     * {@code rootUri} is used, preserving the pre-{@code projectRoot} behavior for clients whose
     * workspace root already is the project folder. Returns null when neither is available.
     */
    public static String projectRootUri(String projectRoot, String rootUri) {
        String workspace = stripTrailingSlash(trimToNull(rootUri));
        String declared = stripTrailingSlash(trimToNull(projectRoot));
        if (declared == null) {
            return workspace;
        }
        if (hasScheme(declared)) {
            return declared;
        }
        String path = declared.replace('\\', '/');
        if (isAbsolutePath(path)) {
            // Windows drive letters ("C:/repo") need the extra slash that POSIX paths already have.
            return "file://" + (path.startsWith("/") ? "" : "/") + encodePath(path);
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isEmpty()) {
            return workspace;
        }
        return workspace == null ? null : workspace + "/" + encodePath(path);
    }

    /**
     * URI of a script resource's {@code code.py}, under the project root resolved by {@link
     * #projectRootUri}.
     */
    public static String scriptUri(String projectRootUri, String project, String resourcePath) {
        String rel = "ignition/script-python/" + resourcePath + "/code.py";
        String base = stripTrailingSlash(trimToNull(projectRootUri));
        if (base != null) {
            return base + "/" + rel;
        }
        return "flint://" + (project != null ? project : "project") + "/" + rel;
    }

    /** True for {@code scheme:...}; the two-character minimum keeps {@code C:} a drive letter. */
    private static boolean hasScheme(String value) {
        int colon = value.indexOf(':');
        if (colon < 2) {
            return false;
        }
        for (int i = 0; i < colon; i++) {
            char c = value.charAt(i);
            boolean ok =
                    (i == 0)
                            ? Character.isLetter(c)
                            : (Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAbsolutePath(String path) {
        if (path.startsWith("/")) {
            return true;
        }
        return path.length() >= 3
                && Character.isLetter(path.charAt(0))
                && path.charAt(1) == ':'
                && path.charAt(2) == '/';
    }

    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder(path.length());
        for (byte raw : path.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 0xFF;
            if (b < 0x80 && PATH_SAFE.indexOf((char) b) >= 0) {
                out.append((char) b);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit(b >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0x0F, 16)));
            }
        }
        return out.toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String out = value;
        while (out.length() > 1 && (out.endsWith("/") || out.endsWith("\\"))) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
