package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.ClassRecord;

/** V54 CQ-012: asked to account for annotation member values and @Nonbinding, Claude searched ~/.m2 for the qualifier's jar. */
class QualifierOriginTest {
    @TempDir Path root;

    @Test void aQualifierWithValuesStatesWhichMembersTakePartInMatching() throws Exception {
        var db = QuillDatabase.create(root.resolve("q.db"));
        IndexWriter.writeIncremental(db, new IndexFixture()
                .classes(List.of(
                        new ClassRecord(0, "x.Consumer", "CLASS", "java.lang.Object", List.of(), "Consumer.java", 1, true, 10),
                        new ClassRecord(0, "x.Tag", "ANNOTATION", null, List.of("java.lang.annotation.Annotation"), "Tag.java", 1, false, 10)))
                .metadata(Map.of("index_id", "q")).snapshot());
        db.useHandle(handle -> handle.execute("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES "
                + "(2,'METHOD','value','value():java.lang.String','()Ljava/lang/String;','java.lang.String','[]','public abstract','[]'),"
                + "(2,'METHOD','note','note():java.lang.String','()Ljava/lang/String;','java.lang.String','[]','public abstract','[\"jakarta.enterprise.util.Nonbinding\"]')"));
        var point = new ObjectMapper().readTree("{\"field\":\"f\",\"qualifiers\":[\"@x.Tag(value = \\\"a\\\")\"]}");
        List<String> lines = TestReads.read(db, reader -> QualifierOrigin.lines(reader, "x.Consumer", point));
        assertTrue(lines.contains("@x.Tag members: value compared; note ignored (@Nonbinding)"), lines.toString());
    }
}
