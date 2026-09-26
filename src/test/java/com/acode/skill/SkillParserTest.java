package com.acode.skill;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SkillParserTest {
    private final SkillParser parser = new SkillParser();
    private SkillDefinition parse(String metadata, String body) {
        return parser.parse("---\n" + metadata + "\n---\n" + body, "sample",
                new SkillSource("test", "sample.md", null));
    }
    @Test void defaultsAndExactBody() {
        var d = parse("name: sample\ndescription: useful", "line\n$ARGUMENTS\nend\n");
        assertEquals("inline", d.mode()); assertEquals("full", d.context());
        assertNull(d.model()); assertEquals(List.of(), d.allowedTools());
        assertEquals("line\n$ARGUMENTS\nend\n", d.body());
        assertEquals("line\nend\n", d.render(null));
        assertEquals("line\n$literal\\value\nend\n", d.render("$literal\\value"));
    }
    @Test void rejectsInvalidMetadataWithSource() {
        for (String metadata : List.of("description: x", "name: sample", "name: Commit\ndescription: x",
                "name: my_skill\ndescription: x", "name: other\ndescription: x",
                "name: sample\ndescription: x\nmode: detached", "name: sample\ndescription: x\ncontext: unknown",
                "name: sample\ndescription: x\nallowedTools: Bash", "name: sample\ndescription: x\nmodel: ''",
                "name: sample\ndescription: x\nname: sample")) {
            var error = assertThrows(IllegalArgumentException.class, () -> parse(metadata, "body"), metadata);
            assertTrue(error.getMessage().contains("sample.md"));
        }
        assertThrows(IllegalArgumentException.class, () -> parse("name: sample\ndescription: x", "$ARGUMENTS $ARGUMENTS"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("body", "sample", new SkillSource("test", "sample.md", null)));
    }
    @Test void allEnumValuesAndSafeYaml() {
        assertEquals("my-skill", parser.parse("---\nname: my-skill\ndescription: valid\n---\nbody", "my-skill",
                new SkillSource("test", "my-skill.md", null)).name());
        for (String mode : List.of("inline", "fork")) for (String context : List.of("full", "recent", "none")) {
            var d = parse("name: sample\ndescription: x\nmode: " + mode + "\ncontext: " + context
                    + "\nallowedTools: [ReadFile, Bash]\nmodel: override", "body");
            assertEquals(mode, d.mode()); assertEquals(context, d.context()); assertEquals("override", d.model());
        }
        assertThrows(IllegalArgumentException.class, () -> parse("!!java.net.URL ['https://example.com']", ""));
    }
}
