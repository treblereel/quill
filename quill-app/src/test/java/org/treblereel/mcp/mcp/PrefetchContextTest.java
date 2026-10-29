package org.treblereel.mcp.mcp;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class PrefetchContextTest {
    @TempDir Path root;
    @Test void arbitraryWordingUsesIndexedIdentifiersAndChangedSourceNeverBecomesFreshFacts() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {int value;} ");
        var first=PrefetchContext.collect(db,root,"Пожалуйста объясни p.Fixture.");
        assertEquals(1,first.path("facts").size(),first.toString());
        assertEquals(first.path("facts"),PrefetchContext.collect(db,root,"Unrelated wording: Fixture?").path("facts"));
        assertEquals(0,PrefetchContext.collect(db,root,"No known identifiers here").path("facts").size());
        Files.writeString(root.resolve("src/main/java/p/Fixture.java"),"package p; class Fixture {long value;}");
        assertEquals(0,PrefetchContext.collect(db,root,"Fixture").path("facts").size());
        org.treblereel.mcp.db.QuillDatabase.retire(db);
    }
}
