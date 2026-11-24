// Kitto agent-tools protocol v1. Copied into each app for M1; moves to a shared lib in M2.
package com.ny0t.kitto;

oneway interface IToolCallback {
    void onResult(String resultJson);
    void onError(String code, String message);
}
