package org.treblereel.mcp.mcp;

import org.treblereel.mcp.db.ReadQueryContext;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class PrefetchContextTest {
    @TempDir Path root;
    @Test void arbitraryWordingUsesIndexedIdentifiersAndChangedSourceNeverBecomesFreshFacts() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {int value;} ");
        var first=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"Пожалуйста объясни p.Fixture."));
        assertEquals(1,first.path("facts").size(),first.toString());
        assertEquals(first.path("facts"),TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"Unrelated wording: Fixture?")).path("facts"));
        assertEquals(1,TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"p.Fixture.value and Fixture.java")).path("facts").size());
        assertEquals(0,TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"No known identifiers here")).path("facts").size());
        Files.writeString(root.resolve("src/main/java/p/Fixture.java"),"package p; class Fixture {long value;}");
        assertEquals(0,TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"Fixture")).path("facts").size());
        
    }
}
