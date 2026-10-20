package com.evolution.analysis.execution.broker;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Authored-fixture tool acquisition. It never extracts or executes the Gradle distribution on the host. */
public final class GradleToolFixtureMain {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);var cas=new StreamingContentStore(root.resolve("cas"));
        URI uri=URI.create("https://downloads.gradle.org/distributions/gradle-8.14.6-bin.zip");
        // Publisher checksum reference and GitHub's release asset size, verified 2026-10-19.
        var pin=new ContentHandle(new ContentDigest("sha256:7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24"),139420601);
        try(var journal=java.nio.channels.FileChannel.open(root.resolve("tool-materials.ndjson"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            java.util.function.Consumer<Object> append=event->{
                try{byte[] bytes=(CanonicalJson.write(event)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())journal.write(buffer);journal.force(true);}
                catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
            };
            var transfer=new RedirectArtifactTransfer(Map.of(uri,pin),List.of(
                    new RedirectArtifactTransfer.HopRule(URI.create("https://downloads.gradle.org/distributions/"),false),
                    new RedirectArtifactTransfer.HopRule(URI.create("https://github.com/gradle/gradle-distributions/releases/download/v8.14.6/"),false),
                    new RedirectArtifactTransfer.HopRule(URI.create("https://release-assets.githubusercontent.com/"),true)),append::accept);
            var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(URI.create("https://downloads.gradle.org/distributions/"))),false,cas,transfer,append::accept,Clock.systemUTC());
            var grant=broker.grant("authored-gradle-tool",1,Duration.ofMinutes(10),268435456);
            var material=broker.acquire(grant,1,uri,Optional.of(pin),()->false);
            System.out.println(CanonicalJson.write(material));
            if(material.state()!=ArtifactBroker.State.ACQUIRED&&material.state()!=ArtifactBroker.State.REUSED)throw new IOException("GRADLE_TOOL_ACQUISITION_NOT_COMPLETE");
        }
    }
}
