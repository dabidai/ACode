package com.acode.skill;

import java.nio.file.Path;

public record SkillSource(String layer, String location, Path file) {
    public static SkillSource file(String layer, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return new SkillSource(layer, absolute.toString(), absolute);
    }
    public String resourceBase() {
        return file == null ? location.substring(0, location.lastIndexOf('/'))
                : file.getParent().toString();
    }
}
