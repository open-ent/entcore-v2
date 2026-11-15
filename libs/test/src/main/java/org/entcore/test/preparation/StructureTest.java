package org.entcore.test.preparation;

public class StructureTest {

    private final String id;
    private final String name;
    private final boolean defaultAuthFederated;
    private final String uai;

    public StructureTest(final String id, final String name) {
        this(id, name, false, null);
    }

    public StructureTest(final String id, final String name, boolean defaultAuthFederated) {
        this(id, name, defaultAuthFederated, null);
    }

    public StructureTest(final String id, final String name, boolean defaultAuthFederated, final String uai) {
        this.id = id;
        this.name = name;
        this.defaultAuthFederated = defaultAuthFederated;
        this.uai = uai;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isDefaultAuthFederated() {
        return defaultAuthFederated;
    }

    public String getUai() {
        return uai;
    }


}
