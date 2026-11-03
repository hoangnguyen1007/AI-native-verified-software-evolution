package com.evolution.backend.query;

import java.nio.file.Files;

/** Optional first-party UI review fixture. Enter a newline to close its server.
 * This deliberately shows a missing sibling and open census, not a fake pass. */
public final class BridgeWorkbenchPreview {
    public static void main(String[] args)throws Exception{
        var fixture=new BridgeQueryApiTest();fixture.directory=Files.createTempDirectory("evo-bridge-ui-");var view=fixture.fixture(args.length==0?java.util.Optional.empty():java.util.Optional.of(java.nio.file.Path.of(args[0])));
        try(var server=new ArchitectureHttpServer(view.api(),args.length>1?Integer.parseInt(args[1]):0)){server.start();System.out.println("BRIDGE_PREVIEW "+server.uri()+"/bridges.html");System.out.println("BRIDGE_SCOPE "+view.input().scope().value());System.in.read();}
    }
    private BridgeWorkbenchPreview(){}
}
