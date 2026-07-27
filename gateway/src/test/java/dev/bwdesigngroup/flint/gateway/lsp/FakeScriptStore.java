package dev.bwdesigngroup.flint.gateway.lsp;

import dev.bwdesigngroup.flint.common.platform.ResourceInfo;
import dev.bwdesigngroup.flint.gateway.resources.GatewayResourceStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link GatewayResourceStore} holding a fixed set of {@code script-python} resources,
 * keyed by slashed resource path ({@code util/math}) with the module's source as the value. Read
 * only — the write methods are unused by the LSP tests.
 */
class FakeScriptStore implements GatewayResourceStore {

    private final String project;
    private final Map<String, String> sources; // resource path -> code

    FakeScriptStore(String project, Map<String, String> sources) {
        this.project = project;
        this.sources = sources;
    }

    /** Store whose modules all have empty bodies — for package-shape tests. */
    static FakeScriptStore ofPaths(String project, String... paths) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String path : paths) {
            sources.put(path, "");
        }
        return new FakeScriptStore(project, sources);
    }

    @Override
    public List<String> listProjectNames() {
        return Arrays.asList(project);
    }

    @Override
    public boolean isProjectAvailable(String p) {
        return project.equals(p);
    }

    @Override
    public String getProjectTitle(String p) {
        return p;
    }

    @Override
    public List<ResourceInfo> getResourcesOfType(String p, String moduleId, String typeId) {
        List<ResourceInfo> out = new ArrayList<>();
        if (project.equals(p) && "ignition".equals(moduleId) && "script-python".equals(typeId)) {
            for (String path : sources.keySet()) {
                out.add(new ResourceInfo(path, moduleId, typeId, path, null));
            }
        }
        return out;
    }

    @Override
    public byte[] readResourceData(ResourceInfo resource, String dataKey) {
        String code = sources.get((String) resource.getNativeResource());
        return code == null ? null : code.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] readDefaultData(ResourceInfo resource) {
        return null;
    }

    @Override
    public String writeResourceData(String p, ResourceInfo r, String k, byte[] d) {
        return null;
    }

    @Override
    public String createResource(
            String p, ResourceInfo t, String m, String ty, String path, String k, byte[] d) {
        return null;
    }

    @Override
    public String deleteResource(String p, ResourceInfo r) {
        return null;
    }
}
