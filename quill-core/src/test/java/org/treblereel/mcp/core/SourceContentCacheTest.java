package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceContentCacheTest {
    @TempDir Path root;
    @Test void warmReuseDetectsSameSizeEditsWithRestoredMtime() throws Exception {
        Path file=root.resolve("sample.java");Files.writeString(file,"aaa");var original=Files.getLastModifiedTime(file);
        var volume=SourceContentCache.volume(root);AtomicInteger reads=new AtomicInteger();
        SourceContentCache.Reader reader=p->{reads.incrementAndGet();return SourceFileHashes.hash(p);};
        String first=SourceContentCache.hash(file,volume,reader);assertEquals(first,SourceContentCache.hash(file,volume,reader));
        if(volume.supported())assertEquals(1,reads.get());
        Files.writeString(file,"bbb");Files.setLastModifiedTime(file,original);
        String changed=SourceContentCache.hash(file,volume,reader);assertNotEquals(first,changed);assertEquals(SourceFileHashes.hash(file),changed);
        assertTrue(reads.get()>=2);
    }
    @Test void missingChangeTimeSupportAlwaysUsesActualContentReads() throws Exception {
        Path file=root.resolve("fallback.java");Files.writeString(file,"abc");var unsupported=new SourceContentCache.Volume(null,false);AtomicInteger reads=new AtomicInteger();
        SourceContentCache.Reader reader=p->{reads.incrementAndGet();return SourceFileHashes.hash(p);};
        assertEquals(SourceContentCache.hash(file,unsupported,reader),SourceContentCache.hash(file,unsupported,reader));assertEquals(2,reads.get());
    }
    @Test void liveEnumerationFindsIgnoredAdditionsDeletionsAndRejectsEscapingSymlinks() throws Exception {
        Path src=Files.createDirectories(root.resolve("target/generated-sources"));Path file=src.resolve("One.java");Files.writeString(file,"class One {}");
        var expected=SourceFileHashes.captureJava(root,List.of(src));assertEquals(expected,SourceFileHashes.captureJavaCached(root,List.of(src)));
        Files.writeString(src.resolve("Two.java"),"class Two {}");assertEquals(2,SourceFileHashes.captureJavaCached(root,List.of(src)).size());
        Files.delete(file);assertFalse(SourceFileHashes.captureJavaCached(root,List.of(src)).containsKey("target/generated-sources/One.java"));
        Path outside=Files.createTempFile("outside-java-cache-",".java");
        try {Files.writeString(outside,"outside");Files.createSymbolicLink(src.resolve("Outside.java"),outside);assertEquals(1,SourceFileHashes.captureJavaCached(root,List.of(src)).size());}
        finally {Files.deleteIfExists(outside);}
    }
    @Test void concurrentMissesCoalesceAndFailuresDoNotPoisonFutureReads() throws Exception {
        var volume=SourceContentCache.volume(root);org.junit.jupiter.api.Assumptions.assumeTrue(volume.supported());
        Path file=root.resolve("shared.java");Files.writeString(file,"abc");AtomicInteger reads=new AtomicInteger();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(4);
        SourceContentCache.Reader reader=p->{reads.incrementAndGet();entered.countDown();try{assertTrue(release.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new java.io.IOException(e);}return SourceFileHashes.hash(p);};
        try {
            var first=executor.submit(()->SourceContentCache.hash(file,volume,reader));assertTrue(entered.await(1,TimeUnit.SECONDS));
            var others=new ArrayList<Future<String>>();for(int i=0;i<3;i++)others.add(executor.submit(()->SourceContentCache.hash(file,volume,reader)));
            release.countDown();String hash=first.get(2,TimeUnit.SECONDS);for(var other:others)assertEquals(hash,other.get(2,TimeUnit.SECONDS));assertEquals(1,reads.get());
        } finally {release.countDown();executor.shutdownNow();}
        Files.writeString(file,"def");
        assertThrows(java.io.IOException.class,()->SourceContentCache.hash(file,volume,p->{throw new java.io.IOException("expected");}));
        assertEquals(SourceFileHashes.hash(file),SourceContentCache.hash(file,volume));
    }
    @Test void writeDuringHashingIsNotCachedAsVerifiedContent() throws Exception {
        var volume=SourceContentCache.volume(root);org.junit.jupiter.api.Assumptions.assumeTrue(volume.supported());
        Path file=root.resolve("race.java");Files.writeString(file,"before");
        assertThrows(java.io.IOException.class,()->SourceContentCache.hash(file,volume,p->{String hash=SourceFileHashes.hash(p);Files.writeString(p,"after!");return hash;}));
        assertEquals(SourceFileHashes.hash(file),SourceContentCache.hash(file,volume));
    }
}
