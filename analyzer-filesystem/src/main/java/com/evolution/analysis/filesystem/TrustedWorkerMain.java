package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;

/** Fixed analyzer-owned entry point. The process receives bytes, never a target command or path. */
public final class TrustedWorkerMain {
    static final String MAGIC = "trusted-worker-v1";

    public static void main(String[] args) throws IOException {
        if (args.length != 1) System.exit(2);
        int maximum;
        try { maximum = Integer.parseInt(args[0]); }
        catch (NumberFormatException invalid) { System.exit(2); return; }
        if (maximum < 1) System.exit(2);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(System.in));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(System.out))) {
            if (!MAGIC.equals(input.readUTF())) System.exit(2);
            int length = input.readInt();
            if (length < 0 || length > maximum) System.exit(2);
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length || input.read() != -1) System.exit(2);
            output.writeUTF(MAGIC);
            output.writeUTF(ContentDigest.sha256(bytes).value());
            output.flush();
        }
    }

    private TrustedWorkerMain() {}
}
