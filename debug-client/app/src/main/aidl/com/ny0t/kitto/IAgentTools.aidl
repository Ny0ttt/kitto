// Kitto agent-tools protocol v1. Copied into each app for M1; moves to a shared lib in M2.
package com.ny0t.kitto;

import com.ny0t.kitto.IToolCallback;

interface IAgentTools {
    /** JSON array describing the tools this app offers. Served from an asset; never starts JS. */
    String listTools();
    /** Runs one tool. The answer arrives on cb (onResult or onError), never as a return value. */
    void callTool(String name, String argsJson, IToolCallback cb);
}
